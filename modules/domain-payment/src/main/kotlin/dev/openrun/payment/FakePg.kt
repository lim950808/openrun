package dev.openrun.payment

import org.slf4j.LoggerFactory
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Fault injected per payment at authentication time (ADR-0007 §2). Values, not probabilities. */
enum class FakeFault { NONE, DECLINE, TIMEOUT, TIMEOUT_THEN_INQUIRY_DOWN, DELAY }

data class FakePayment(val paymentKey: String, val orderId: String, val amount: Int)

/**
 * In-process PG (ADR-0007 §2). [authorize] stands in for the Toss payment widget's authentication step and
 * remembers the fault; the [PgClient] calls then behave per fault:
 *
 * - `DECLINE`: confirm → Declined(REJECT_CARD_COMPANY)
 * - `TIMEOUT`: confirm approves on the PG side, then returns Unknown (the response was lost)
 * - `TIMEOUT_THEN_INQUIRY_DOWN`: as TIMEOUT, and inquire returns Unknown until `inquiryDownSeconds` after the
 *   confirm call (by [clock])
 * - `DELAY`: confirm sleeps `delaySeconds` (real time), then approves
 *
 * Confirm is idempotent per idempotency key like Toss: a repeated key returns the first result.
 */
class FakePg(
    private val clock: Clock,
    private val webhookSigner: WebhookSignature? = null,
    private val webhookUrl: String? = null,
) : PgClient {
    private val log = LoggerFactory.getLogger(javaClass)

    private class State(
        val payment: FakePayment,
        val fault: FakeFault,
        val delaySeconds: Long,
        val inquiryDownSeconds: Long,
    ) {
        @Volatile var status: PgPaymentStatus = PgPaymentStatus.IN_PROGRESS
        @Volatile var approvedAt: Instant? = null
        @Volatile var confirmedAt: Instant? = null
        val confirmCalls = AtomicInteger()
    }

    private val byKey = ConcurrentHashMap<String, State>()
    private val byOrder = ConcurrentHashMap<String, State>()
    private val replies = ConcurrentHashMap<String, ConfirmResult>()
    private val http by lazy { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build() }
    private val executor by lazy { Executors.newVirtualThreadPerTaskExecutor() }
    private val json = JsonMapper.builder().build()

    fun authorize(orderId: String, amount: Int, fault: FakeFault = FakeFault.NONE, delaySeconds: Long = 0, inquiryDownSeconds: Long = 0): FakePayment {
        val payment = FakePayment("fake_" + UUID.randomUUID().toString().replace("-", ""), orderId, amount)
        val state = State(payment, fault, delaySeconds, inquiryDownSeconds)
        byKey[payment.paymentKey] = state
        byOrder[orderId] = state
        return payment
    }

    /** Number of confirm calls that reached the PG for [orderId] (M3-7). */
    fun confirmCalls(orderId: String): Int = byOrder[orderId]?.confirmCalls?.get() ?: 0

    override fun confirm(paymentKey: String, orderId: String, amount: Int, idempotencyKey: String): ConfirmResult {
        val state = byKey[paymentKey]
        if (state == null || state.payment.orderId != orderId) return ConfirmResult.Declined("NOT_FOUND_PAYMENT_SESSION", "unknown paymentKey")
        state.confirmCalls.incrementAndGet()
        replies[idempotencyKey]?.let { return it }
        if (state.payment.amount != amount) return ConfirmResult.Declined("INVALID_REQUEST", "amount mismatch")
        val result = when (state.fault) {
            FakeFault.DECLINE -> {
                state.status = PgPaymentStatus.ABORTED
                ConfirmResult.Declined("REJECT_CARD_COMPANY", "fake decline")
            }
            FakeFault.TIMEOUT, FakeFault.TIMEOUT_THEN_INQUIRY_DOWN -> {
                approve(state)
                ConfirmResult.Unknown("fake read timeout")
            }
            FakeFault.DELAY -> {
                Thread.sleep(state.delaySeconds * 1000)
                approve(state)
            }
            FakeFault.NONE -> approve(state)
        }
        replies[idempotencyKey] = result
        if (result is ConfirmResult.Approved) sendWebhook(state)
        return result
    }

    override fun inquire(orderId: String): InquiryResult {
        val state = byOrder[orderId] ?: return InquiryResult.NotFound
        if (state.fault == FakeFault.TIMEOUT_THEN_INQUIRY_DOWN) {
            val upAt = state.confirmedAt?.plusSeconds(state.inquiryDownSeconds)
            if (upAt != null && clock.instant().isBefore(upAt)) return InquiryResult.Unknown("fake inquiry down")
        }
        return InquiryResult.Found(state.status, state.payment.paymentKey, state.approvedAt, state.payment.amount)
    }

    override fun cancel(paymentKey: String, reason: String, idempotencyKey: String): CancelResult {
        val state = byKey[paymentKey] ?: return CancelResult.Declined("NOT_FOUND_PAYMENT")
        if (state.status == PgPaymentStatus.CANCELED) return CancelResult.AlreadyCancelled
        state.status = PgPaymentStatus.CANCELED
        return CancelResult.Cancelled(clock.instant())
    }

    private fun approve(state: State): ConfirmResult.Approved {
        val now = clock.instant()
        state.status = PgPaymentStatus.DONE
        state.approvedAt = now
        state.confirmedAt = now
        return ConfirmResult.Approved(state.payment.paymentKey, now, state.payment.amount)
    }

    /** Signed PAYMENT_STATUS_CHANGED(DONE) to our own webhook endpoint, fire and forget. */
    private fun sendWebhook(state: State) {
        val signer = webhookSigner ?: return
        val url = webhookUrl ?: return
        val payload = json.writeValueAsString(
            mapOf(
                "eventType" to "PAYMENT_STATUS_CHANGED",
                "createdAt" to clock.instant().toString(),
                "data" to mapOf(
                    "paymentKey" to state.payment.paymentKey,
                    "orderId" to state.payment.orderId,
                    "status" to PgPaymentStatus.DONE.name,
                    "totalAmount" to state.payment.amount,
                    "approvedAt" to state.approvedAt.toString(),
                ),
            ),
        )
        executor.execute {
            try {
                val time = clock.instant().epochSecond.toString()
                val request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("tosspayments-webhook-signature", signer.sign(payload, time))
                    .header("tosspayments-webhook-transmission-time", time)
                    .header("tosspayments-webhook-transmission-id", UUID.randomUUID().toString())
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build()
                val res = http.send(request, HttpResponse.BodyHandlers.discarding())
                if (res.statusCode() != 200) log.warn("fake-pg webhook {} -> {}", state.payment.orderId, res.statusCode())
            } catch (e: Exception) {
                log.warn("fake-pg webhook {} failed: {}", state.payment.orderId, e.toString())
            }
        }
    }
}
