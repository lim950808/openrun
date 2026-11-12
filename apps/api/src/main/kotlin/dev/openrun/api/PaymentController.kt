package dev.openrun.api

import dev.openrun.payment.PaymentError
import dev.openrun.payment.PaymentProperties
import dev.openrun.payment.PaymentResult
import dev.openrun.payment.PaymentService
import dev.openrun.payment.PaymentStatus
import dev.openrun.payment.PaymentView
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

data class ConfirmRequest(val orderId: String = "", val paymentKey: String = "", val amount: Int = 0)

/**
 * Payment API (ADR-0007 §4). Owner check by access token or `X-Member-Id` ([CallerIdentity.forReservation]);
 * no admission token is required (requirements §1). Confirm answers 202 while the payment is CONFIRMING · UNKNOWN.
 */
@RestController
class PaymentController(
    private val payments: PaymentService,
    private val callers: CallerIdentity,
    private val errors: ApiErrors,
    private val props: PaymentProperties,
) {
    @PostMapping("/api/reservations/{reservationId}/payments")
    fun start(
        @PathVariable reservationId: Long,
        @RequestHeader("Authorization", required = false) authorization: String?,
        @RequestHeader("X-Member-Id", required = false) memberHeader: Long?,
    ): ResponseEntity<Any> {
        val memberId = when (val caller = callers.forReservation(authorization, memberHeader)) {
            is Caller.Member -> caller.memberId
            is Caller.Rejected -> return caller.response
        }
        return respond(payments.start(reservationId, memberId)) { ResponseEntity.ok(it) }
    }

    @PostMapping("/api/payments/confirm")
    fun confirm(
        @RequestHeader("Authorization", required = false) authorization: String?,
        @RequestHeader("X-Member-Id", required = false) memberHeader: Long?,
        @RequestBody body: ConfirmRequest,
    ): ResponseEntity<Any> {
        val memberId = when (val caller = callers.forReservation(authorization, memberHeader)) {
            is Caller.Member -> caller.memberId
            is Caller.Rejected -> return caller.response
        }
        return respond(payments.confirm(body.orderId, body.paymentKey, body.amount, memberId)) { viewResponse(it) }
    }

    @PostMapping("/api/reservations/{reservationId}/cancel")
    fun cancel(
        @PathVariable reservationId: Long,
        @RequestHeader("Authorization", required = false) authorization: String?,
        @RequestHeader("X-Member-Id", required = false) memberHeader: Long?,
    ): ResponseEntity<Any> {
        val memberId = when (val caller = callers.forReservation(authorization, memberHeader)) {
            is Caller.Member -> caller.memberId
            is Caller.Rejected -> return caller.response
        }
        return respond(payments.cancel(reservationId, memberId)) { ResponseEntity.ok(it) }
    }

    @GetMapping("/api/reservations/{reservationId}")
    fun reservation(
        @PathVariable reservationId: Long,
        @RequestHeader("Authorization", required = false) authorization: String?,
        @RequestHeader("X-Member-Id", required = false) memberHeader: Long?,
    ): ResponseEntity<Any> {
        val memberId = when (val caller = callers.forReservation(authorization, memberHeader)) {
            is Caller.Member -> caller.memberId
            is Caller.Rejected -> return caller.response
        }
        return respond(payments.view(reservationId, memberId)) { ResponseEntity.ok(it) }
    }

    /** Public: the static payment page needs the client key to open the Toss widget. */
    @GetMapping("/api/payments/config")
    fun config(): Map<String, String> = mapOf("pg" to props.pg, "clientKey" to props.toss.clientKey)

    private fun viewResponse(view: PaymentView): ResponseEntity<Any> =
        if (view.status == PaymentStatus.CONFIRMING || view.status == PaymentStatus.UNKNOWN) {
            ResponseEntity.status(HttpStatus.ACCEPTED).body(view)
        } else {
            ResponseEntity.ok(view)
        }

    private fun <T> respond(result: PaymentResult<T>, ok: (T) -> ResponseEntity<Any>): ResponseEntity<Any> =
        when (result) {
            is PaymentResult.Success -> ok(result.value)
            is PaymentResult.Failure -> errors.respond(statusOf(result.error), result.error.code)
        }

    private fun statusOf(error: PaymentError): HttpStatus = when (error) {
        PaymentError.ReservationNotFound, PaymentError.PaymentNotFound -> HttpStatus.NOT_FOUND
        PaymentError.NotOwner -> HttpStatus.FORBIDDEN
        PaymentError.AmountMismatch -> HttpStatus.BAD_REQUEST
        PaymentError.IllegalTransition, PaymentError.HoldExpired, PaymentError.ShowStarted -> HttpStatus.CONFLICT
        PaymentError.PgCancelFailed -> HttpStatus.BAD_GATEWAY
    }
}
