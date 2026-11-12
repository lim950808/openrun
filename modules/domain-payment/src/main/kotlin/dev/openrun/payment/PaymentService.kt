package dev.openrun.payment

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant

enum class PaymentError(val code: String) {
    ReservationNotFound("RESERVATION_NOT_FOUND"),
    PaymentNotFound("PAYMENT_NOT_FOUND"),
    NotOwner("NOT_OWNER"),
    AmountMismatch("AMOUNT_MISMATCH"),
    IllegalTransition("ILLEGAL_TRANSITION"),
    HoldExpired("HOLD_EXPIRED"),
    ShowStarted("SHOW_STARTED"),
    PgCancelFailed("PG_CANCEL_FAILED"),
}

sealed interface PaymentResult<out T> {
    data class Success<T>(val value: T) : PaymentResult<T>

    data class Failure(val error: PaymentError) : PaymentResult<Nothing>
}

/** `POST /api/reservations/{id}/payments` response. `customerKey` is what the Toss widget wants per member. */
data class PaymentStart(val orderId: String, val amount: Int, val attempt: Int, val customerKey: String)

data class PaymentView(
    val status: PaymentStatus,
    val reservationStatus: String,
    val orderId: String,
    val paymentKey: String?,
    val approvedAt: Instant?,
    val amount: Int,
    val attempt: Int,
    val failureCode: String?,
)

data class ReservationView(
    val reservationId: Long,
    val showtimeId: Long,
    val status: String,
    val expiresAt: Instant,
    val totalAmount: Int,
    val seatIds: List<Long>,
    val payment: PaymentView?,
)

/**
 * Payment use cases (ADR-0007 §4). JDBC only (ADR-0003). PG calls run outside transactions: commit
 * REQUESTED → CONFIRMING, call, then commit the result (ADR-0004 §4).
 *
 * Counters (process-wide, no showtime tag): `openrun.payment.amount_mismatch`, `openrun.payment.idempotent_replay`,
 * `openrun.payment.cancel_requests`, `openrun.payment.cancel_success`.
 */
@Service
class PaymentService(
    private val jdbc: JdbcTemplate,
    private val tx: TransactionTemplate,
    private val transitions: PaymentTransitions,
    private val pg: PgClient,
    private val clock: Clock,
    private val registry: MeterRegistry,
) {
    private data class Reservation(val id: Long, val showtimeId: Long, val memberId: Long, val status: String, val expiresAt: Instant, val totalAmount: Int)

    private sealed interface Prepared {
        data class Reject(val error: PaymentError) : Prepared

        data class Replay(val view: PaymentView) : Prepared

        data class Call(val payment: PaymentRow) : Prepared
    }

    /** HOLDING → PAYING + payment REQUESTED in one transaction. Idempotent while the reservation is PAYING. */
    fun start(reservationId: Long, memberId: Long): PaymentResult<PaymentStart> {
        val now = clock.instant()
        return tx.execute { status ->
            when (val row = transitions.holdingToPaying(reservationId, memberId, now)) {
                is StartRow.Started -> {
                    val p = transitions.request(reservationId, row.totalAmount)
                    PaymentResult.Success(startOf(p, memberId))
                }
                StartRow.SeatsLost -> {
                    status.setRollbackOnly()
                    PaymentResult.Failure(PaymentError.HoldExpired)
                }
                StartRow.NoRow -> {
                    val r = reservation(reservationId) ?: return@execute PaymentResult.Failure(PaymentError.ReservationNotFound)
                    val active = transitions.latestFor(reservationId)
                    when {
                        r.memberId != memberId -> PaymentResult.Failure(PaymentError.NotOwner)
                        r.status == ReservationState.PAYING && active != null && active.status != PaymentStatus.FAILED ->
                            PaymentResult.Success(startOf(active, memberId))
                        r.status == ReservationState.HOLDING -> PaymentResult.Failure(PaymentError.HoldExpired)
                        else -> PaymentResult.Failure(PaymentError.IllegalTransition)
                    }
                }
            }
        }!!
    }

    /**
     * REQUESTED → CONFIRMING → PG confirm → result (ADR-0004 §3). A request that finds the payment already past
     * REQUESTED is a replay: no PG call, current view returned (`idempotent_replay_count`).
     */
    fun confirm(orderId: String, paymentKey: String, amount: Int, memberId: Long): PaymentResult<PaymentView> {
        val prepared = tx.execute { prepare(orderId, amount, memberId, clock.instant()) }!!
        val p = when (prepared) {
            is Prepared.Reject -> return PaymentResult.Failure(prepared.error)
            is Prepared.Replay -> return PaymentResult.Success(prepared.view)
            is Prepared.Call -> prepared.payment
        }
        val result = pg.confirm(paymentKey, p.orderId, p.amount, "${p.reservationId}:${p.attempt}")
        tx.executeWithoutResult { applyConfirmResult(p.id, result, clock.instant()) }
        return PaymentResult.Success(view(p.id))
    }

    private fun prepare(orderId: String, amount: Int, memberId: Long, now: Instant): Prepared {
        val p = transitions.byOrderId(orderId) ?: return Prepared.Reject(PaymentError.PaymentNotFound)
        val r = reservation(p.reservationId)!!
        if (r.memberId != memberId) return Prepared.Reject(PaymentError.NotOwner)
        if (amount != p.amount) {
            registry.counter(AMOUNT_MISMATCH).increment()
            return Prepared.Reject(PaymentError.AmountMismatch)
        }
        if (p.status != PaymentStatus.REQUESTED) return replay(p.id)
        if (r.status != ReservationState.PAYING) return Prepared.Reject(PaymentError.IllegalTransition)
        if (!r.expiresAt.isAfter(now)) {
            // Expiry chain, committed now (ADR-0004 §3): REQUESTED → FAILED, then PAYING → EXPIRED.
            if (transitions.toFailed(p, HOLD_EXPIRED_CODE, now)) return Prepared.Reject(PaymentError.HoldExpired)
            return replay(p.id)
        }
        if (!transitions.requestedToConfirming(p, now)) return replay(p.id)
        return Prepared.Call(p.copy(status = PaymentStatus.CONFIRMING))
    }

    private fun replay(paymentId: Long): Prepared.Replay {
        registry.counter(IDEMPOTENT_REPLAY).increment()
        return Prepared.Replay(view(paymentId))
    }

    /**
     * One PG confirm outcome → one transition row. The payment is re-read: the janitor may have moved CONFIRMING to
     * UNKNOWN meanwhile, and a late 200 then applies UNKNOWN → CONFIRMED.
     */
    internal fun applyConfirmResult(paymentId: Long, result: ConfirmResult, now: Instant) {
        val p = transitions.byId(paymentId)!!
        when (result) {
            is ConfirmResult.Approved -> transitions.toConfirmed(p, result.paymentKey, result.approvedAt, now)
            is ConfirmResult.Declined -> transitions.toFailed(p, result.code, now)
            is ConfirmResult.Unknown -> transitions.toUnknown(p, now, result.cause)
        }
    }

    /** CONFIRMED → CANCELLED by the owner before the show starts (M3-8). */
    fun cancel(reservationId: Long, memberId: Long): PaymentResult<PaymentView> {
        registry.counter(CANCEL_REQUESTS).increment()
        val r = reservation(reservationId) ?: return PaymentResult.Failure(PaymentError.ReservationNotFound)
        if (r.memberId != memberId) return PaymentResult.Failure(PaymentError.NotOwner)
        if (r.status != ReservationState.CONFIRMED) return PaymentResult.Failure(PaymentError.IllegalTransition)
        val startsAt = jdbc.queryForObject("select starts_at from showtime where id = ?", { rs, _ -> rs.instant("starts_at")!! }, r.showtimeId)!!
        if (!startsAt.isAfter(clock.instant())) return PaymentResult.Failure(PaymentError.ShowStarted)
        val p = transitions.latestFor(reservationId)
        if (p == null || p.status != PaymentStatus.CONFIRMED || p.paymentKey == null) return PaymentResult.Failure(PaymentError.IllegalTransition)

        when (pg.cancel(p.paymentKey, USER_CANCEL_REASON, "cancel:${p.id}")) {
            is CancelResult.Cancelled, CancelResult.AlreadyCancelled -> Unit
            is CancelResult.Declined, is CancelResult.Unknown -> return PaymentResult.Failure(PaymentError.PgCancelFailed)
        }
        val moved = tx.execute { transitions.toCancelled(p) }!!
        if (!moved) return PaymentResult.Failure(PaymentError.IllegalTransition)
        registry.counter(CANCEL_SUCCESS).increment()
        return PaymentResult.Success(view(p.id))
    }

    fun view(reservationId: Long, memberId: Long): PaymentResult<ReservationView> {
        val r = reservation(reservationId) ?: return PaymentResult.Failure(PaymentError.ReservationNotFound)
        if (r.memberId != memberId) return PaymentResult.Failure(PaymentError.NotOwner)
        val payment = transitions.latestFor(reservationId)?.let { view(it.id) }
        return PaymentResult.Success(
            ReservationView(r.id, r.showtimeId, r.status, r.expiresAt, r.totalAmount, transitions.seatIds(r.id), payment),
        )
    }

    private fun view(paymentId: Long): PaymentView =
        jdbc.queryForObject(
            """
            select p.status, r.status as reservation_status, p.order_id, p.payment_key, p.approved_at, p.amount, p.attempt, p.failure_code
            from payment p join reservation r on r.id = p.reservation_id
            where p.id = ?
            """.trimIndent(),
            { rs, _ ->
                PaymentView(
                    status = PaymentStatus.valueOf(rs.getString("status")),
                    reservationStatus = rs.getString("reservation_status"),
                    orderId = rs.getString("order_id"),
                    paymentKey = rs.getString("payment_key"),
                    approvedAt = rs.instant("approved_at"),
                    amount = rs.getInt("amount"),
                    attempt = rs.getInt("attempt"),
                    failureCode = rs.getString("failure_code"),
                )
            },
            paymentId,
        )!!

    private fun reservation(id: Long): Reservation? =
        jdbc.query(
            "select id, showtime_id, member_id, status, expires_at, total_amount from reservation where id = ?",
            { rs, _ ->
                Reservation(
                    rs.getLong("id"), rs.getLong("showtime_id"), rs.getLong("member_id"), rs.getString("status"),
                    rs.instant("expires_at")!!, rs.getInt("total_amount"),
                )
            },
            id,
        ).singleOrNull()

    private fun startOf(p: PaymentRow, memberId: Long) = PaymentStart(p.orderId, p.amount, p.attempt, "member-$memberId")

    companion object {
        const val AMOUNT_MISMATCH = "openrun.payment.amount_mismatch"
        const val IDEMPOTENT_REPLAY = "openrun.payment.idempotent_replay"
        const val CANCEL_REQUESTS = "openrun.payment.cancel_requests"
        const val CANCEL_SUCCESS = "openrun.payment.cancel_success"
        const val HOLD_EXPIRED_CODE = "HOLD_EXPIRED"
        const val USER_CANCEL_REASON = "customer request"
    }
}
