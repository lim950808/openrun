package dev.openrun.booking.hold

import dev.openrun.outbox.OutboxJdbcRepository
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

/**
 * HOLDING → EXPIRED once `expires_at <= now()` (ADR-0004 §2). Seats are already AVAILABLE by derivation at
 * `expires_at`; this job writes the state, the `ReservationExpired` events and the Redis cleanup (`expire_lag_s`).
 *
 * PAYING → EXPIRED (M3, ADR-0007 §5): only when the payment REQUESTED → FAILED update hits the row first, in the
 * same statement. A payment already CONFIRMING · UNKNOWN · CONFIRMED keeps its reservation (ADR-0004 §2 rule 2);
 * UNKNOWN past the release window is the payment janitor's job.
 */
@Component
class HoldExpiryJanitor(
    private val jdbc: JdbcTemplate,
    private val tx: TransactionTemplate,
    private val outbox: OutboxJdbcRepository,
    private val redisHolds: RedisSeatHolds,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private data class ExpiredSeat(
        val reservationId: Long,
        val showtimeId: Long,
        val expiresAt: Instant,
        val seatId: Long,
        val freed: Boolean,
    )

    @Scheduled(fixedDelayString = "\${openrun.hold.janitor-delay-ms:5000}")
    @SchedulerLock(name = "hold-expiry", lockAtMostFor = "1m", lockAtLeastFor = "1s")
    fun scheduledSweep() {
        sweep()
    }

    private data class PayingExpiry(
        val reservationId: Long,
        val showtimeId: Long,
        val expiresAt: Instant,
        val paymentId: Long,
        val orderId: String,
        val attempt: Int,
        val amount: Int,
        val seatId: Long,
    )

    /** Expires every overdue HOLDING reservation, then every overdue PAYING one whose payment is still REQUESTED. */
    fun sweep(): Int = sweepHolding() + sweepPaying()

    /** Expires every overdue HOLDING reservation in one transaction. Returns the number of reservations expired. */
    private fun sweepHolding(): Int {
        val now = Instant.now()
        val rows = tx.execute {
            // One reservation row per seat it held; `freed` is false when the seat was already re-claimed lazily
            // by someone else, in which case seat_hold and the Redis key belong to the new holder and stay untouched.
            val rows = jdbc.query(
                """
                with expired as (
                    update reservation set status = ?, updated_at = now()
                    where status = ? and expires_at <= now()
                    returning id, showtime_id, expires_at
                ), freed as (
                    update seat_hold sh set reservation_id = null, member_id = null, expires_at = null
                    from expired e
                    where sh.showtime_id = e.showtime_id and sh.reservation_id = e.id
                    returning sh.reservation_id as old_reservation_id, sh.seat_id
                )
                select e.id, e.showtime_id, e.expires_at, rs.seat_id,
                       exists (select 1 from freed f where f.old_reservation_id = e.id and f.seat_id = rs.seat_id) as freed
                from expired e join reservation_seat rs on rs.reservation_id = e.id
                order by e.id, rs.seat_id
                """.trimIndent(),
                { rs, _ ->
                    ExpiredSeat(
                        reservationId = rs.getLong("id"),
                        showtimeId = rs.getLong("showtime_id"),
                        expiresAt = rs.getObject("expires_at", OffsetDateTime::class.java).toInstant(),
                        seatId = rs.getLong("seat_id"),
                        freed = rs.getBoolean("freed"),
                    )
                },
                ReservationStatus.EXPIRED.name,
                ReservationStatus.HOLDING.name,
            )
            rows.groupBy { it.reservationId }.forEach { (id, seats) ->
                val first = seats.first()
                outbox.append(ReservationEvents.expired(id, first.showtimeId, seats.map { it.seatId }, first.expiresAt))
            }
            rows
        }.orEmpty()

        val byReservation = rows.groupBy { it.reservationId }
        byReservation.forEach { (id, seats) ->
            redisHolds.release(seats.first().showtimeId, seats.filter { it.freed }.map { it.seatId }, id)
        }
        if (byReservation.isNotEmpty()) {
            val maxLag = byReservation.values.maxOf { Duration.between(it.first().expiresAt, now) }
            log.info("hold-expiry expired={} max_lag_ms={}", byReservation.size, maxLag.toMillis())
        }
        return byReservation.size
    }

    /**
     * payment REQUESTED → FAILED first, then reservation PAYING → EXPIRED only for the rows that update hit, then the
     * seats. One statement, so a concurrent confirm that already moved the payment to CONFIRMING (row lock, then
     * re-check under READ COMMITTED) leaves both rows untouched.
     */
    private fun sweepPaying(): Int {
        val rows = tx.execute {
            val rows = jdbc.query(
                """
                with failed as (
                    update payment p set status = 'FAILED', failure_code = 'HOLD_EXPIRED', updated_at = now()
                    from reservation r
                    where r.id = p.reservation_id and r.status = ? and r.expires_at <= now() and p.status = 'REQUESTED'
                    returning p.id as payment_id, p.reservation_id, p.order_id, p.attempt, p.amount
                ), expired as (
                    update reservation r set status = ?, updated_at = now()
                    from failed f
                    where r.id = f.reservation_id and r.status = ?
                    returning r.id, r.showtime_id, r.expires_at
                ), freed as (
                    update seat_hold sh set reservation_id = null, member_id = null, expires_at = null
                    from expired e
                    where sh.showtime_id = e.showtime_id and sh.reservation_id = e.id
                    returning sh.seat_id
                )
                -- `freed` runs although unreferenced: data-modifying CTEs always execute to completion.
                select e.id, e.showtime_id, e.expires_at, f.payment_id, f.order_id, f.attempt, f.amount, rs.seat_id
                from expired e
                join failed f on f.reservation_id = e.id
                join reservation_seat rs on rs.reservation_id = e.id
                order by e.id, rs.seat_id
                """.trimIndent(),
                { rs, _ ->
                    PayingExpiry(
                        reservationId = rs.getLong("id"),
                        showtimeId = rs.getLong("showtime_id"),
                        expiresAt = rs.getObject("expires_at", OffsetDateTime::class.java).toInstant(),
                        paymentId = rs.getLong("payment_id"),
                        orderId = rs.getString("order_id"),
                        attempt = rs.getInt("attempt"),
                        amount = rs.getInt("amount"),
                        seatId = rs.getLong("seat_id"),
                    )
                },
                ReservationStatus.PAYING.name,
                ReservationStatus.EXPIRED.name,
                ReservationStatus.PAYING.name,
            )
            rows.groupBy { it.reservationId }.forEach { (id, seats) ->
                val f = seats.first()
                outbox.append(ReservationEvents.paymentFailed(f.paymentId, id, f.orderId, f.attempt, f.amount))
                outbox.append(ReservationEvents.expired(id, f.showtimeId, seats.map { it.seatId }, f.expiresAt))
            }
            rows
        }.orEmpty()
        val byReservation = rows.groupBy { it.reservationId }
        byReservation.forEach { (id, seats) -> redisHolds.release(seats.first().showtimeId, seats.map { it.seatId }, id) }
        if (byReservation.isNotEmpty()) log.info("hold-expiry paying expired={}", byReservation.size)
        return byReservation.size
    }
}
