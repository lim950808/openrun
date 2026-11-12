package dev.openrun.booking.hold

import dev.openrun.outbox.OutboxEvent
import java.time.Instant

/**
 * Outbox events for the reservation aggregate (glossary 도메인 이벤트). Payloads hold only numbers, enum names
 * and ISO instants, so they are written as JSON literals without a mapper dependency in this module.
 */
internal object ReservationEvents {
    private const val AGGREGATE = "reservation"

    fun held(r: HoldResult, showtimeId: Long, memberId: Long, strategy: LockStrategy) = OutboxEvent(
        aggregateType = AGGREGATE,
        aggregateId = r.reservationId.toString(),
        eventType = "ReservationHeld",
        payload = """{"reservationId":${r.reservationId},"showtimeId":$showtimeId,"memberId":$memberId,""" +
            """"seatIds":${r.seats.map { it.seatId }},"totalAmount":${r.totalAmount},""" +
            """"expiresAt":"${r.expiresAt}","strategy":"${strategy.name}"}""",
    )

    fun cancelled(reservationId: Long, showtimeId: Long, memberId: Long, seatIds: List<Long>) = OutboxEvent(
        aggregateType = AGGREGATE,
        aggregateId = reservationId.toString(),
        eventType = "ReservationCancelled",
        payload = """{"reservationId":$reservationId,"showtimeId":$showtimeId,"memberId":$memberId,"seatIds":$seatIds}""",
    )

    fun expired(reservationId: Long, showtimeId: Long, seatIds: List<Long>, expiresAt: Instant) = OutboxEvent(
        aggregateType = AGGREGATE,
        aggregateId = reservationId.toString(),
        eventType = "ReservationExpired",
        payload = """{"reservationId":$reservationId,"showtimeId":$showtimeId,"seatIds":$seatIds,"expiresAt":"$expiresAt"}""",
    )

    /**
     * PAYING expiry chain's payment side (ADR-0007 §5 last paragraph), same payload shape as domain-payment's
     * PaymentEvents (the janitor writes both rows in one statement, so the event is built here).
     */
    fun paymentFailed(paymentId: Long, reservationId: Long, orderId: String, attempt: Int, amount: Int) = OutboxEvent(
        aggregateType = "payment",
        aggregateId = paymentId.toString(),
        eventType = "PaymentFailed",
        payload = """{"paymentId":$paymentId,"reservationId":$reservationId,"orderId":"$orderId","attempt":$attempt,""" +
            """"amount":$amount,"from":"REQUESTED","failureCode":"HOLD_EXPIRED"}""",
    )
}
