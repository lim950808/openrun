package dev.openrun.payment

import dev.openrun.outbox.OutboxJdbcRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

data class PaymentRow(
    val id: Long,
    val reservationId: Long,
    val attempt: Int,
    val orderId: String,
    val amount: Int,
    val status: PaymentStatus,
    val paymentKey: String?,
    val approvedAt: Instant?,
    val failureCode: String?,
    val confirmingSince: Instant?,
    val unknownSince: Instant?,
)

data class ReservationRef(val id: Long, val showtimeId: Long, val memberId: Long, val seatIds: List<Long>, val expiresAt: Instant)

/** Reservation states as stored (ADR-0004 §2). domain-payment does not depend on domain-booking's enum. */
object ReservationState {
    const val HOLDING = "HOLDING"
    const val PAYING = "PAYING"
    const val CONFIRMED = "CONFIRMED"
    const val EXPIRED = "EXPIRED"
    const val CANCELLED = "CANCELLED"
}

enum class ApproveOutcome {
    /** Reservation PAYING → CONFIRMED in the same transaction. */
    CONFIRMED,

    /** Reservation was no longer PAYING (confirm-expire race): CompensationRequested + payment_compensation row. */
    COMPENSATION_REQUESTED,
}

sealed interface StartRow {
    data class Started(val reservation: ReservationRef, val totalAmount: Int) : StartRow

    /** The conditional UPDATE matched 0 rows. */
    data object NoRow : StartRow

    /** The reservation row moved but its seats were already re-claimed (expired at the boundary): roll back. */
    data object SeatsLost : StartRow
}

/**
 * Every payment and payment-driven reservation transition, one method per row of ADR-0004 §2·§3, each a
 * conditional UPDATE (`where status = :from`) plus its outbox event. Callers own the transaction: every method here
 * must run inside one. A method that returns false / null matched 0 rows and changed nothing.
 *
 * Seat side (ADR-0004 §1): PAYING and CONFIRMED seats stay HELD/SOLD whatever `expires_at` says, so HOLDING → PAYING
 * sets `seat_hold.expires_at = 'infinity'` (every claim strategy's free-check is `expires_at < now()`), PAYING →
 * HOLDING restores the reservation's `expires_at`, and → EXPIRED · CANCELLED clears the rows.
 */
@Component
class PaymentTransitions(
    private val jdbc: JdbcTemplate,
    private val outbox: OutboxJdbcRepository,
    private val hook: SeatHoldHook,
) {
    // ---- reads ---------------------------------------------------------------------------------------------

    fun byOrderId(orderId: String): PaymentRow? =
        jdbc.query("select * from payment where order_id = ?", paymentMapper, orderId).singleOrNull()

    fun byId(id: Long): PaymentRow? = jdbc.query("select * from payment where id = ?", paymentMapper, id).singleOrNull()

    fun latestFor(reservationId: Long): PaymentRow? =
        jdbc.query("select * from payment where reservation_id = ? order by attempt desc limit 1", paymentMapper, reservationId)
            .singleOrNull()

    fun seatIds(reservationId: Long): List<Long> =
        jdbc.query("select seat_id from reservation_seat where reservation_id = ? order by seat_id", { rs, _ -> rs.getLong(1) }, reservationId)

    // ---- reservation rows (ADR-0004 §2) --------------------------------------------------------------------

    /** HOLDING → PAYING. Guard: owner, `expires_at > now`, and every seat still held by this reservation. */
    fun holdingToPaying(reservationId: Long, memberId: Long, now: Instant): StartRow {
        val moved = jdbc.query(
            """
            update reservation set status = ?, updated_at = now()
            where id = ? and member_id = ? and status = ? and expires_at > ?
            returning showtime_id, member_id, expires_at, total_amount
            """.trimIndent(),
            { rs, _ -> Moved(rs.getLong("showtime_id"), rs.getLong("member_id"), rs.instant("expires_at")!!, rs.getInt("total_amount")) },
            ReservationState.PAYING, reservationId, memberId, ReservationState.HOLDING, now.utc(),
        ).singleOrNull() ?: return StartRow.NoRow
        val seatIds = seatIds(reservationId)
        val pinned = jdbc.update(
            "update seat_hold set expires_at = 'infinity' where showtime_id = ? and reservation_id = ?",
            moved.showtimeId, reservationId,
        )
        if (pinned != seatIds.size) return StartRow.SeatsLost
        val ref = ReservationRef(reservationId, moved.showtimeId, moved.memberId, seatIds, moved.expiresAt)
        hook.pin(ref.showtimeId, seatIds, reservationId)
        outbox.append(PaymentEvents.reservation(PaymentEvents.RESERVATION_PAYMENT_STARTED, ref))
        return StartRow.Started(ref, moved.totalAmount)
    }

    /** PAYING → CONFIRMED, only as the side effect of payment → CONFIRMED. */
    private fun payingToConfirmed(reservationId: Long): ReservationRef? =
        moveReservation(
            """
            update reservation set status = ?, updated_at = now()
            where id = ? and status = ?
            returning showtime_id, member_id, expires_at
            """.trimIndent(),
            reservationId, ReservationState.CONFIRMED, reservationId, ReservationState.PAYING,
        )?.also { outbox.append(PaymentEvents.reservation(PaymentEvents.RESERVATION_CONFIRMED, it)) }

    /** PAYING → HOLDING, side effect of payment → FAILED while the hold is still valid (retry with a new attempt). */
    private fun payingToHolding(reservationId: Long, now: Instant): ReservationRef? {
        val ref = moveReservation(
            """
            update reservation set status = ?, updated_at = now()
            where id = ? and status = ? and expires_at > ?
            returning showtime_id, member_id, expires_at
            """.trimIndent(),
            reservationId, ReservationState.HOLDING, reservationId, ReservationState.PAYING, now.utc(),
        ) ?: return null
        jdbc.update(
            "update seat_hold set expires_at = ? where showtime_id = ? and reservation_id = ?",
            ref.expiresAt.utc(), ref.showtimeId, reservationId,
        )
        hook.unpin(ref.showtimeId, ref.seatIds, reservationId, ref.expiresAt)
        outbox.append(PaymentEvents.reservation(PaymentEvents.RESERVATION_PAYMENT_FAILED, ref))
        return ref
    }

    /**
     * PAYING → EXPIRED once `expires_at <= now`. Callers check the second half of the guard: the payment
     * REQUESTED → FAILED update hit 1 row, or the payment is FAILED now, or it has been UNKNOWN past the release window.
     */
    fun payingToExpired(reservationId: Long, now: Instant): ReservationRef? {
        val ref = moveReservation(
            """
            update reservation set status = ?, updated_at = now()
            where id = ? and status = ? and expires_at <= ?
            returning showtime_id, member_id, expires_at
            """.trimIndent(),
            reservationId, ReservationState.EXPIRED, reservationId, ReservationState.PAYING, now.utc(),
        ) ?: return null
        clearSeats(ref)
        outbox.append(PaymentEvents.reservation(PaymentEvents.RESERVATION_EXPIRED, ref, mapOf("expiresAt" to ref.expiresAt.toString())))
        return ref
    }

    /** CONFIRMED → CANCELLED, side effect of payment CONFIRMED → CANCELLED (user cancel, CANCELED webhook). */
    private fun confirmedToCancelled(reservationId: Long): ReservationRef? {
        val ref = moveReservation(
            """
            update reservation set status = ?, updated_at = now()
            where id = ? and status = ?
            returning showtime_id, member_id, expires_at
            """.trimIndent(),
            reservationId, ReservationState.CANCELLED, reservationId, ReservationState.CONFIRMED,
        ) ?: return null
        clearSeats(ref)
        outbox.append(PaymentEvents.reservation(PaymentEvents.RESERVATION_CANCELLED, ref))
        return ref
    }

    // ---- payment rows (ADR-0004 §3) ------------------------------------------------------------------------

    /** none → REQUESTED. attempt = previous max + 1, orderId = `or-{reservationId}-{attempt}` (6+ chars for any id: Toss accepts 6–64). */
    fun request(reservationId: Long, amount: Int): PaymentRow {
        val attempt = jdbc.queryForObject(
            "select coalesce(max(attempt), 0) + 1 from payment where reservation_id = ?",
            Int::class.java,
            reservationId,
        )!!
        val p = jdbc.query(
            """
            insert into payment (reservation_id, attempt, order_id, amount, status)
            values (?, ?, ?, ?, ?)
            returning *
            """.trimIndent(),
            paymentMapper,
            reservationId, attempt, "or-$reservationId-$attempt", amount, PaymentStatus.REQUESTED.name,
        ).single()
        outbox.append(PaymentEvents.payment(PaymentEvents.PAYMENT_REQUESTED, p))
        return p
    }

    /** REQUESTED → CONFIRMING. The caller has checked amount, owner, reservation PAYING and `expires_at > now`. */
    fun requestedToConfirming(p: PaymentRow, now: Instant): Boolean {
        if (!allowed(p.status, PaymentStatus.CONFIRMING) || p.status != PaymentStatus.REQUESTED) return false
        val n = jdbc.update(
            "update payment set status = ?, confirming_since = ?, updated_at = now() where id = ? and status = ?",
            PaymentStatus.CONFIRMING.name, now.utc(), p.id, PaymentStatus.REQUESTED.name,
        )
        if (n == 0) return false
        outbox.append(PaymentEvents.payment(PaymentEvents.PAYMENT_CONFIRMING, p))
        return true
    }

    /** CONFIRMING → UNKNOWN (timeout · 5xx · network, or janitor after `confirming-timeout`). */
    fun toUnknown(p: PaymentRow, now: Instant, cause: String): Boolean {
        if (!allowed(p.status, PaymentStatus.UNKNOWN)) return false
        val n = jdbc.update(
            "update payment set status = ?, unknown_since = ?, updated_at = now() where id = ? and status = ?",
            PaymentStatus.UNKNOWN.name, now.utc(), p.id, p.status.name,
        )
        if (n == 0) return false
        outbox.append(PaymentEvents.payment(PaymentEvents.PAYMENT_UNKNOWN, p, mapOf("cause" to cause)))
        return true
    }

    /**
     * CONFIRMING · UNKNOWN → CONFIRMED, then reservation PAYING → CONFIRMED. If the reservation is no longer PAYING
     * (only EXPIRED is reachable), the payment transition still commits and compensation is queued in the same
     * transaction (ADR-0004 §4). null = the payment row did not match.
     */
    fun toConfirmed(p: PaymentRow, paymentKey: String, approvedAt: Instant?, now: Instant): ApproveOutcome? {
        if (!allowed(p.status, PaymentStatus.CONFIRMED)) return null
        val n = jdbc.update(
            """
            update payment set status = ?, payment_key = ?, approved_at = ?,
                   unknown_resolved_at = case when status = ? then ? else unknown_resolved_at end,
                   updated_at = now()
            where id = ? and status = ?
            """.trimIndent(),
            PaymentStatus.CONFIRMED.name, paymentKey, (approvedAt ?: now).utc(),
            PaymentStatus.UNKNOWN.name, now.utc(),
            p.id, p.status.name,
        )
        if (n == 0) return null
        outbox.append(PaymentEvents.payment(PaymentEvents.PAYMENT_CONFIRMED, p, mapOf("from" to p.status.name, "paymentKey" to paymentKey)))
        if (payingToConfirmed(p.reservationId) != null) return ApproveOutcome.CONFIRMED
        jdbc.update("insert into payment_compensation (payment_id) values (?) on conflict do nothing", p.id)
        outbox.append(PaymentEvents.payment(PaymentEvents.COMPENSATION_REQUESTED, p, mapOf("paymentKey" to paymentKey)))
        return ApproveOutcome.COMPENSATION_REQUESTED
    }

    /**
     * REQUESTED · CONFIRMING · UNKNOWN → FAILED, then reservation PAYING → HOLDING (hold still valid) or EXPIRED.
     * A reservation already EXPIRED (UNKNOWN release path) is left alone.
     */
    fun toFailed(p: PaymentRow, code: String, now: Instant): Boolean {
        if (!allowed(p.status, PaymentStatus.FAILED)) return false
        val n = jdbc.update(
            """
            update payment set status = ?, failure_code = ?,
                   unknown_resolved_at = case when status = ? then ? else unknown_resolved_at end,
                   updated_at = now()
            where id = ? and status = ?
            """.trimIndent(),
            PaymentStatus.FAILED.name, code.take(64), PaymentStatus.UNKNOWN.name, now.utc(), p.id, p.status.name,
        )
        if (n == 0) return false
        outbox.append(PaymentEvents.payment(PaymentEvents.PAYMENT_FAILED, p, mapOf("from" to p.status.name, "failureCode" to code)))
        payingToHolding(p.reservationId, now) ?: payingToExpired(p.reservationId, now)
        return true
    }

    /**
     * CONFIRMED → CANCELLED. Reservation CONFIRMED → CANCELLED for a user cancel; a compensated reservation stays
     * EXPIRED and its compensation row is closed.
     */
    fun toCancelled(p: PaymentRow): Boolean {
        if (!allowed(p.status, PaymentStatus.CANCELLED)) return false
        val n = jdbc.update(
            "update payment set status = ?, updated_at = now() where id = ? and status = ?",
            PaymentStatus.CANCELLED.name, p.id, p.status.name,
        )
        if (n == 0) return false
        outbox.append(PaymentEvents.payment(PaymentEvents.PAYMENT_CANCELLED, p))
        confirmedToCancelled(p.reservationId)
        jdbc.update("update payment_compensation set done_at = now() where payment_id = ? and done_at is null", p.id)
        return true
    }

    // ---- helpers -------------------------------------------------------------------------------------------

    private data class Moved(val showtimeId: Long, val memberId: Long, val expiresAt: Instant, val totalAmount: Int)

    private fun allowed(from: PaymentStatus, to: PaymentStatus) = to in allowedTransitions.getValue(from)

    private fun moveReservation(sql: String, reservationId: Long, vararg args: Any): ReservationRef? {
        val moved = jdbc.query(sql, { rs, _ -> Moved(rs.getLong("showtime_id"), rs.getLong("member_id"), rs.instant("expires_at")!!, 0) }, *args)
            .singleOrNull() ?: return null
        return ReservationRef(reservationId, moved.showtimeId, moved.memberId, seatIds(reservationId), moved.expiresAt)
    }

    /** Frees only rows this reservation still owns. */
    private fun clearSeats(ref: ReservationRef) {
        jdbc.update(
            "update seat_hold set reservation_id = null, member_id = null, expires_at = null where showtime_id = ? and reservation_id = ?",
            ref.showtimeId, ref.id,
        )
        hook.release(ref.showtimeId, ref.seatIds, ref.id)
    }

    companion object {
        val paymentMapper = RowMapper { rs, _ ->
            PaymentRow(
                id = rs.getLong("id"),
                reservationId = rs.getLong("reservation_id"),
                attempt = rs.getInt("attempt"),
                orderId = rs.getString("order_id"),
                amount = rs.getInt("amount"),
                status = PaymentStatus.valueOf(rs.getString("status")),
                paymentKey = rs.getString("payment_key"),
                approvedAt = rs.instant("approved_at"),
                failureCode = rs.getString("failure_code"),
                confirmingSince = rs.instant("confirming_since"),
                unknownSince = rs.instant("unknown_since"),
            )
        }
    }
}

internal fun Instant.utc(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)

internal fun java.sql.ResultSet.instant(column: String): Instant? = getObject(column, OffsetDateTime::class.java)?.toInstant()
