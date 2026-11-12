package dev.openrun.payment

import dev.openrun.outbox.OutboxEvent
import tools.jackson.databind.json.JsonMapper

/**
 * Outbox events for the transitions [PaymentTransitions] performs (ADR-0004 §2·§3 side-effect columns): the payment
 * aggregate's own events plus the reservation-side events of the transitions that share its transaction.
 */
internal object PaymentEvents {
    private const val PAYMENT = "payment"
    private const val RESERVATION = "reservation"
    private val json = JsonMapper.builder().build()

    fun payment(type: String, p: PaymentRow, extra: Map<String, Any?> = emptyMap()) = OutboxEvent(
        aggregateType = PAYMENT,
        aggregateId = p.id.toString(),
        eventType = type,
        payload = json.writeValueAsString(
            linkedMapOf<String, Any?>(
                "paymentId" to p.id,
                "reservationId" to p.reservationId,
                "orderId" to p.orderId,
                "attempt" to p.attempt,
                "amount" to p.amount,
            ) + extra,
        ),
    )

    fun reservation(type: String, r: ReservationRef, extra: Map<String, Any?> = emptyMap()) = OutboxEvent(
        aggregateType = RESERVATION,
        aggregateId = r.id.toString(),
        eventType = type,
        payload = json.writeValueAsString(
            linkedMapOf<String, Any?>(
                "reservationId" to r.id,
                "showtimeId" to r.showtimeId,
                "memberId" to r.memberId,
                "seatIds" to r.seatIds,
            ) + extra,
        ),
    )

    const val PAYMENT_REQUESTED = "PaymentRequested"
    const val PAYMENT_CONFIRMING = "PaymentConfirming"
    const val PAYMENT_CONFIRMED = "PaymentConfirmed"
    const val PAYMENT_FAILED = "PaymentFailed"
    const val PAYMENT_UNKNOWN = "PaymentUnknown"
    const val PAYMENT_CANCELLED = "PaymentCancelled"
    const val COMPENSATION_REQUESTED = "CompensationRequested"

    const val RESERVATION_PAYMENT_STARTED = "ReservationPaymentStarted"
    const val RESERVATION_CONFIRMED = "ReservationConfirmed"
    const val RESERVATION_PAYMENT_FAILED = "ReservationPaymentFailed"
    const val RESERVATION_EXPIRED = "ReservationExpired"
    const val RESERVATION_CANCELLED = "ReservationCancelled"
}
