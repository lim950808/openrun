package dev.openrun.payment

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.http.HttpClient
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Base64

/**
 * Toss Payments core API (ADR-0007 §1). `Authorization: Basic base64("{secretKey}:")`; `Idempotency-Key` on POST.
 * No retries here: a timeout is [ConfirmResult.Unknown] and the payment janitor's inquiry settles it.
 * Transport failures are caught at this boundary and turned into `Unknown`, so callers never see exceptions.
 */
class TossPgClient(
    builder: RestClient.Builder,
    secretKey: String,
    baseUrl: String = DEFAULT_BASE_URL,
) : PgClient {
    private val log = LoggerFactory.getLogger(javaClass)
    private val json = JsonMapper.builder().build()
    private val client: RestClient = builder
        .baseUrl(baseUrl)
        .defaultHeader("Authorization", "Basic " + Base64.getEncoder().encodeToString("$secretKey:".toByteArray()))
        .build()

    private data class Reply(val status: HttpStatusCode, val body: JsonNode?)

    override fun confirm(paymentKey: String, orderId: String, amount: Int, idempotencyKey: String): ConfirmResult {
        val body = json.writeValueAsString(mapOf("paymentKey" to paymentKey, "orderId" to orderId, "amount" to amount))
        val reply = post("/v1/payments/confirm", body, idempotencyKey) ?: return ConfirmResult.Unknown("transport")
        if (reply.status.is2xxSuccessful) {
            val payment = reply.body ?: return ConfirmResult.Unknown("empty body")
            return when (PgPaymentStatus.parse(payment.str("status"))) {
                PgPaymentStatus.DONE -> ConfirmResult.Approved(
                    paymentKey = payment.str("paymentKey") ?: paymentKey,
                    approvedAt = payment.instant("approvedAt"),
                    totalAmount = payment.get("totalAmount")?.asInt() ?: amount,
                )
                else -> ConfirmResult.Unknown("status ${payment.str("status")}")
            }
        }
        val code = reply.body?.str("code") ?: "HTTP_${reply.status.value()}"
        val message = reply.body?.str("message") ?: ""
        return when {
            code in UNKNOWN_CODES || reply.status.is5xxServerError -> ConfirmResult.Unknown(code)
            else -> ConfirmResult.Declined(code, message)
        }
    }

    override fun inquire(orderId: String): InquiryResult {
        val reply = get("/v1/payments/orders/{orderId}", orderId) ?: return InquiryResult.Unknown("transport")
        if (reply.status.is2xxSuccessful) {
            val payment = reply.body ?: return InquiryResult.Unknown("empty body")
            val status = PgPaymentStatus.parse(payment.str("status")) ?: return InquiryResult.Unknown("status ${payment.str("status")}")
            return InquiryResult.Found(
                status = status,
                paymentKey = payment.str("paymentKey"),
                approvedAt = payment.instant("approvedAt"),
                totalAmount = payment.get("totalAmount")?.asInt() ?: 0,
            )
        }
        val code = reply.body?.str("code")
        return if (reply.status.value() == 404 || code == "NOT_FOUND_PAYMENT") InquiryResult.NotFound else InquiryResult.Unknown(code ?: "HTTP_${reply.status.value()}")
    }

    override fun cancel(paymentKey: String, reason: String, idempotencyKey: String): CancelResult {
        val body = json.writeValueAsString(mapOf("cancelReason" to reason))
        val reply = post("/v1/payments/$paymentKey/cancel", body, idempotencyKey) ?: return CancelResult.Unknown("transport")
        if (reply.status.is2xxSuccessful) {
            val canceledAt = reply.body?.get("cancels")?.lastOrNull()?.instant("canceledAt")
            return CancelResult.Cancelled(canceledAt)
        }
        val code = reply.body?.str("code") ?: "HTTP_${reply.status.value()}"
        return when {
            code == "ALREADY_CANCELED_PAYMENT" -> CancelResult.AlreadyCancelled
            code in UNKNOWN_CODES || reply.status.is5xxServerError -> CancelResult.Unknown(code)
            else -> CancelResult.Declined(code)
        }
    }

    private fun post(path: String, body: String, idempotencyKey: String): Reply? = call {
        client.post().uri(path)
            .header("Idempotency-Key", idempotencyKey)
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .exchange { _, res -> Reply(res.statusCode, parse(res.body.readAllBytes())) }
    }

    private fun get(path: String, vararg vars: Any): Reply? = call {
        client.get().uri(path, *vars).exchange { _, res -> Reply(res.statusCode, parse(res.body.readAllBytes())) }
    }

    /** Timeouts and connection errors arrive as [RestClientException] (ResourceAccessException). */
    private fun call(block: () -> Reply?): Reply? = try {
        block()
    } catch (e: RestClientException) {
        log.warn("toss call failed: {}", e.toString())
        null
    }

    private fun parse(bytes: ByteArray): JsonNode? =
        if (bytes.isEmpty()) null else runCatching { json.readTree(bytes) }.getOrNull()

    companion object {
        const val DEFAULT_BASE_URL = "https://api.tosspayments.com"

        /** Toss error codes that do not prove the payment failed (ADR-0007 §1). */
        val UNKNOWN_CODES = setOf("ALREADY_PROCESSED_PAYMENT", "PROVIDER_ERROR", "IDEMPOTENT_REQUEST_PROCESSING", "FAILED_INTERNAL_SYSTEM_PROCESSING")

        /** Connect 3 s, read 10 s (ADR-0007 §1). */
        fun requestFactory(connectTimeout: Duration = Duration.ofSeconds(3), readTimeout: Duration = Duration.ofSeconds(10)) =
            JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(connectTimeout).build()).apply {
                setReadTimeout(readTimeout)
            }
    }
}

internal fun JsonNode.str(name: String): String? = get(name)?.takeUnless { it.isNull }?.asString()

internal fun JsonNode.instant(name: String): Instant? = str(name)?.let { OffsetDateTime.parse(it).toInstant() }
