package dev.openrun.api

import dev.openrun.booking.hold.HoldService
import dev.openrun.payment.FakeFault
import dev.openrun.payment.FakePg
import dev.openrun.payment.PaymentIntegrityQuery
import dev.openrun.payment.PaymentJanitor
import dev.openrun.payment.PaymentResult
import dev.openrun.payment.PaymentService
import dev.openrun.payment.PaymentStatus
import dev.openrun.payment.PaymentView
import dev.openrun.payment.WebhookSignature
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.assertj.MockMvcTester
import org.springframework.test.web.servlet.assertj.MvcTestResult
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** M3-6: webhooks are verified, stored, then applied by the janitor with the rank + table rule (ADR-0004 §5). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig::class)
class WebhookInboxTest @Autowired constructor(
    private val mvc: MockMvcTester,
    private val admin: AdminController,
    private val holds: HoldService,
    private val payments: PaymentService,
    private val fakePg: FakePg,
    private val janitor: PaymentJanitor,
    private val paymentIntegrity: PaymentIntegrityQuery,
    private val jdbc: JdbcTemplate,
    private val signature: WebhookSignature,
) {
    private val members = AtomicLong(System.nanoTime() % 1_000_000_000 + 5_000_000_000)

    private data class Paying(val showtimeId: Long, val reservationId: Long, val memberId: Long, val orderId: String, val amount: Int)

    private fun paying(): Paying {
        val showtime = admin.newShowtime()
        val member = members.incrementAndGet()
        val held = holds.holdSeats(showtime.showtimeId, member, showtime.seatIds.getValue("S").first())
        val start = payments.started(held.reservationId, member)
        return Paying(showtime.showtimeId, held.reservationId, member, start.orderId, start.amount)
    }

    private fun confirm(p: Paying, fault: FakeFault = FakeFault.NONE): PaymentView {
        val key = fakePg.authorize(p.orderId, p.amount, fault).paymentKey
        return (payments.confirm(p.orderId, key, p.amount, p.memberId) as PaymentResult.Success).value
    }

    private fun body(orderId: String, status: String, createdAt: String = Instant.now().toString(), paymentKey: String = "pk_$orderId") =
        """{"eventType":"PAYMENT_STATUS_CHANGED","createdAt":"$createdAt",""" +
            """"data":{"paymentKey":"$paymentKey","orderId":"$orderId","status":"$status","totalAmount":90000}}"""

    private fun send(payload: String, sig: String? = null): MvcTestResult {
        val time = Instant.now().epochSecond.toString()
        return mvc.post().uri("/api/webhooks/toss")
            .contentType(MediaType.APPLICATION_JSON)
            .header("tosspayments-webhook-signature", sig ?: signature.sign(payload, time))
            .header("tosspayments-webhook-transmission-time", time)
            .header("tosspayments-webhook-transmission-id", UUID.randomUUID().toString())
            .content(payload)
            .exchange()
    }

    private fun outcomes(orderId: String): List<String?> =
        jdbc.queryForList("select outcome from payment_event_inbox where order_id = ? order by id", String::class.java, orderId)

    @Test
    fun `M3_6 서명이 틀리면 401이고 저장하지 않는다`() {
        val orderId = "sig-${UUID.randomUUID()}"

        assertThat(send(body(orderId, "DONE"), sig = "v1:" + "A".repeat(44)))
            .hasStatus(HttpStatus.UNAUTHORIZED).bodyJson().extractingPath("$.code").isEqualTo("WEBHOOK_SIGNATURE_INVALID")
        assertThat(send(body(orderId, "DONE"), sig = WebhookSignature("other-secret").sign(body(orderId, "DONE"), "0")))
            .hasStatus(HttpStatus.UNAUTHORIZED)
        assertThat(outcomes(orderId)).isEmpty()
    }

    @Test
    fun `M3_6 같은 웹훅 재전송은 DUPLICATE로 남고 한 번만 적용된다`() {
        val p = paying()
        assertThat(confirm(p, FakeFault.TIMEOUT).status).isEqualTo(PaymentStatus.UNKNOWN)
        val payload = body(p.orderId, "DONE", createdAt = "2026-11-28T20:00:01.000000")

        assertThat(send(payload)).hasStatusOk()
        assertThat(send(payload)).hasStatusOk()
        assertThat(outcomes(p.orderId)).containsExactly(null, "DUPLICATE")

        janitor.tick(Instant.now())

        assertThat(outcomes(p.orderId)).containsExactly("APPLIED", "DUPLICATE")
        assertThat(jdbc.paymentStatus(p.orderId)).isEqualTo("CONFIRMED")
        assertThat(jdbc.reservationStatus(p.reservationId)).isEqualTo("CONFIRMED")
        assertThat(paymentIntegrity.check(p.showtimeId).webhookDuplicateCount).isEqualTo(1)
    }

    @Test
    fun `M3_6 역순 웹훅 CANCELED 다음 DONE은 STALE`() {
        val p = paying()
        assertThat(confirm(p).status).isEqualTo(PaymentStatus.CONFIRMED)

        assertThat(send(body(p.orderId, "CANCELED", createdAt = "2026-11-28T20:05:00"))).hasStatusOk()
        assertThat(send(body(p.orderId, "DONE", createdAt = "2026-11-28T20:01:00"))).hasStatusOk()
        janitor.tick(Instant.now())

        assertThat(outcomes(p.orderId)).containsExactly("APPLIED", "STALE")
        assertThat(jdbc.paymentStatus(p.orderId)).isEqualTo("CANCELLED")
        assertThat(jdbc.reservationStatus(p.reservationId)).isEqualTo("CANCELLED")
        assertThat(paymentIntegrity.check(p.showtimeId).webhookStaleCount).isEqualTo(1)
    }

    @Test
    fun `M3_6 우리가 이미 CONFIRMED로 만든 뒤 늦게 온 DONE은 STALE`() {
        val p = paying()
        assertThat(confirm(p).status).isEqualTo(PaymentStatus.CONFIRMED)

        assertThat(send(body(p.orderId, "DONE"))).hasStatusOk()
        janitor.tick(Instant.now())

        assertThat(outcomes(p.orderId)).containsExactly("STALE")
        assertThat(jdbc.paymentStatus(p.orderId)).isEqualTo("CONFIRMED")
    }

    @Test
    fun `M3_6 과거 attempt의 orderId 웹훅은 현재 attempt에 영향이 없다`() {
        val p = paying()
        assertThat(confirm(p, FakeFault.DECLINE).status).isEqualTo(PaymentStatus.FAILED)
        val retry = payments.started(p.reservationId, p.memberId)
        assertThat(retry.orderId).isEqualTo("or-${p.reservationId}-2")

        assertThat(send(body(p.orderId, "DONE"))).hasStatusOk()
        janitor.tick(Instant.now())

        assertThat(outcomes(p.orderId)).containsExactly("STALE")
        assertThat(jdbc.paymentStatus(p.orderId)).isEqualTo("FAILED")
        assertThat(jdbc.paymentStatus(retry.orderId)).isEqualTo("REQUESTED")
        assertThat(jdbc.reservationStatus(p.reservationId)).isEqualTo("PAYING")
    }

    @Test
    fun `M3_6 모르는 orderId는 ORPHAN, 진행 중 상태는 IGNORED`() {
        val orphan = "orphan-${UUID.randomUUID()}"
        val p = paying()

        assertThat(send(body(orphan, "DONE"))).hasStatusOk()
        assertThat(send(body(p.orderId, "IN_PROGRESS"))).hasStatusOk()
        janitor.tick(Instant.now())

        assertThat(outcomes(orphan)).containsExactly("ORPHAN")
        assertThat(outcomes(p.orderId)).containsExactly("IGNORED")
        assertThat(jdbc.paymentStatus(p.orderId)).isEqualTo("REQUESTED")
    }

    @Test
    fun `M3_6 수신 응답은 1초 이하다`() {
        val orderId = "ack-${UUID.randomUUID()}"
        val millis = (1..50).map { i ->
            val started = System.nanoTime()
            assertThat(send(body(orderId, "DONE", createdAt = "2026-11-28T20:00:$i"))).hasStatusOk()
            (System.nanoTime() - started) / 1_000_000
        }.sorted()

        assertThat(millis[(millis.size * 99 / 100).coerceAtMost(millis.size - 1)]).isLessThan(1_000)
    }
}
