package dev.openrun.api

import dev.openrun.payment.WebhookInbox
import dev.openrun.payment.WebhookReceipt
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.time.Duration

/**
 * Toss webhook receiver (ADR-0007 §4): verify, store in the inbox, 200. No processing inline; the payment janitor
 * applies stored rows. `openrun.webhook.ack` times receipt to response (`webhook_ack_p99_ms`).
 */
@RestController
class WebhookController(
    private val inbox: WebhookInbox,
    private val errors: ApiErrors,
    registry: MeterRegistry,
) {
    private val ack: Timer = Timer.builder(ACK_METRIC)
        .publishPercentiles(0.5, 0.99)
        .distributionStatisticExpiry(Duration.ofMinutes(10))
        .distributionStatisticBufferLength(1)
        .register(registry)

    @PostMapping("/api/webhooks/toss")
    fun receive(
        @RequestBody payload: String,
        @RequestHeader("tosspayments-webhook-signature", required = false) signature: String?,
        @RequestHeader("tosspayments-webhook-transmission-time", required = false) transmissionTime: String?,
        @RequestHeader("tosspayments-webhook-transmission-id", required = false) transmissionId: String?,
    ): ResponseEntity<Any> {
        val sample = Timer.start()
        try {
            return when (inbox.receive(payload, transmissionTime, signature, transmissionId)) {
                is WebhookReceipt.Stored -> ResponseEntity.ok().build()
                WebhookReceipt.SignatureInvalid -> errors.respond(HttpStatus.UNAUTHORIZED, "WEBHOOK_SIGNATURE_INVALID")
                WebhookReceipt.Malformed -> errors.respond(HttpStatus.BAD_REQUEST, "INVALID_BODY")
            }
        } finally {
            sample.stop(ack)
        }
    }

    companion object {
        const val ACK_METRIC = "openrun.webhook.ack"
    }
}
