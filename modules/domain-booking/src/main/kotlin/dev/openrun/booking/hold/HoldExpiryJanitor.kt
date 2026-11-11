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

    /** Expires every overdue HOLDING reservation in one transaction. Returns the number of reservations expired. */
    fun sweep(): Int {
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
}
