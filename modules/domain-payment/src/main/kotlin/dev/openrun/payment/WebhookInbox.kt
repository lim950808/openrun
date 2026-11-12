package dev.openrun.payment

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import java.security.MessageDigest
import java.util.HexFormat

enum class InboxOutcome { APPLIED, STALE, DUPLICATE, IGNORED, ORPHAN }

sealed interface WebhookReceipt {
    data class Stored(val eventKey: String, val duplicate: Boolean) : WebhookReceipt

    /** Signature check failed: nothing stored, 401. */
    data object SignatureInvalid : WebhookReceipt

    /** Signed but not JSON: nothing stored, 400. */
    data object Malformed : WebhookReceipt
}

/**
 * Webhook receipt (ADR-0007 §4·§5): verify, store, return. Applying is [PaymentJanitor]'s first path.
 *
 * Every signed receipt is stored. `event_key` = body `eventId` when present, else
 * `sha256(eventType|orderId|status|createdAt)` (PAYMENT_STATUS_CHANGED has no eventId). The first receipt of a key
 * is pending (`outcome` null); a later receipt of the same key is stored with outcome DUPLICATE and `applied_at` set
 * in the same statement, enforced by the partial unique index `ux_inbox_event_key` (so concurrent duplicates are
 * also caught). `webhook_duplicate_count` is then a count of DUPLICATE rows.
 */
@Component
class WebhookInbox(
    private val jdbc: JdbcTemplate,
    private val signature: WebhookSignature,
) {
    private val json = JsonMapper.builder().build()

    fun receive(payload: String, transmissionTime: String?, signatureHeader: String?, transmissionId: String?): WebhookReceipt {
        if (!signature.verify(payload, transmissionTime, signatureHeader)) return WebhookReceipt.SignatureInvalid
        val body = try {
            json.readTree(payload)
        } catch (e: JacksonException) {
            return WebhookReceipt.Malformed
        }
        if (body == null || !body.isObject) return WebhookReceipt.Malformed
        val eventType = body.str("eventType") ?: "UNKNOWN"
        val data = body.get("data")
        val orderId = data?.str("orderId")
        val status = data?.str("status")
        val eventKey = body.str("eventId") ?: sha256("$eventType|$orderId|$status|${body.str("createdAt")}")
        val args = arrayOf<Any?>(eventKey, eventType.take(64), orderId?.take(64), data?.str("paymentKey")?.take(200), status?.take(32), transmissionId?.take(128), payload)

        val inserted = jdbc.update(
            """
            insert into payment_event_inbox (event_key, event_type, order_id, payment_key, pg_status, transmission_id, payload)
            values (?, ?, ?, ?, ?, ?, ?::jsonb)
            on conflict (event_key) where outcome is null or outcome <> 'DUPLICATE' do nothing
            """.trimIndent(),
            *args,
        )
        if (inserted == 1) return WebhookReceipt.Stored(eventKey, duplicate = false)
        jdbc.update(
            """
            insert into payment_event_inbox (event_key, event_type, order_id, payment_key, pg_status, transmission_id, payload, applied_at, outcome)
            values (?, ?, ?, ?, ?, ?, ?::jsonb, now(), 'DUPLICATE')
            """.trimIndent(),
            *args,
        )
        return WebhookReceipt.Stored(eventKey, duplicate = true)
    }

    private fun sha256(s: String): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.toByteArray()))
}
