package dev.openrun.payment

import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withServerError
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.net.InetSocketAddress
import java.time.Duration
import java.time.Instant
import java.util.Base64

/** ADR-0007 §1 error mapping. Real Toss is not called; the shapes follow the Toss API docs (2026-11-12). */
class TossPgClientTest {
    private val builder = RestClient.builder()
    private val server = MockRestServiceServer.bindTo(builder).build()
    private val client = TossPgClient(builder, "test_sk_abc", "https://api.tosspayments.com")

    private val done = """
        {"paymentKey":"pk_1","orderId":"or-7-1","status":"DONE","totalAmount":150000,"approvedAt":"2026-11-28T20:00:05+09:00"}
    """.trimIndent()

    private fun error(code: String) = """{"code":"$code","message":"msg $code"}"""

    @Test
    fun `M3_1 200 DONE은 Approved, Basic 인증과 멱등키 헤더를 보낸다`() {
        server.expect(requestTo("https://api.tosspayments.com/v1/payments/confirm"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization", "Basic " + Base64.getEncoder().encodeToString("test_sk_abc:".toByteArray())))
            .andExpect(header("Idempotency-Key", "7:1"))
            .andExpect(content().string("""{"paymentKey":"pk_1","orderId":"or-7-1","amount":150000}"""))
            .andRespond(withSuccess(done, MediaType.APPLICATION_JSON))

        val result = client.confirm("pk_1", "or-7-1", 150_000, "7:1")

        assertThat(result).isEqualTo(ConfirmResult.Approved("pk_1", Instant.parse("2026-11-28T11:00:05Z"), 150_000))
        server.verify()
    }

    @Test
    fun `M3_1 4xx 확정 실패는 Declined`() {
        server.expect(requestTo("https://api.tosspayments.com/v1/payments/confirm"))
            .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON).body(error("REJECT_CARD_COMPANY")))

        assertThat(client.confirm("pk_1", "or-7-1", 150_000, "7:1")).isEqualTo(ConfirmResult.Declined("REJECT_CARD_COMPANY", "msg REJECT_CARD_COMPANY"))
    }

    @Test
    fun `M3_4 5xx, ALREADY_PROCESSED_PAYMENT, 409 IDEMPOTENT_REQUEST_PROCESSING은 Unknown`() {
        server.expect(requestTo("https://api.tosspayments.com/v1/payments/confirm")).andRespond(withServerError())
        server.expect(requestTo("https://api.tosspayments.com/v1/payments/confirm"))
            .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON).body(error("ALREADY_PROCESSED_PAYMENT")))
        server.expect(requestTo("https://api.tosspayments.com/v1/payments/confirm"))
            .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON).body(error("IDEMPOTENT_REQUEST_PROCESSING")))

        repeat(3) { assertThat(client.confirm("pk_1", "or-7-1", 150_000, "7:1")).isInstanceOf(ConfirmResult.Unknown::class.java) }
        server.verify()
    }

    @Test
    fun `M3_4 조회 200은 Found, 404는 NotFound`() {
        server.expect(requestTo("https://api.tosspayments.com/v1/payments/orders/or-7-1"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess(done, MediaType.APPLICATION_JSON))
        server.expect(requestTo("https://api.tosspayments.com/v1/payments/orders/or-7-2"))
            .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON).body(error("NOT_FOUND_PAYMENT")))

        assertThat(client.inquire("or-7-1")).isEqualTo(InquiryResult.Found(PgPaymentStatus.DONE, "pk_1", Instant.parse("2026-11-28T11:00:05Z"), 150_000))
        assertThat(client.inquire("or-7-2")).isEqualTo(InquiryResult.NotFound)
    }

    @Test
    fun `M3_5 취소 200은 Cancelled, ALREADY_CANCELED_PAYMENT는 AlreadyCancelled`() {
        server.expect(requestTo("https://api.tosspayments.com/v1/payments/pk_1/cancel"))
            .andExpect(header("Idempotency-Key", "comp:9"))
            .andExpect(content().string("""{"cancelReason":"hold expired before approval"}"""))
            .andRespond(withSuccess("""{"status":"CANCELED","cancels":[{"canceledAt":"2026-11-28T20:10:00+09:00"}]}""", MediaType.APPLICATION_JSON))
        server.expect(requestTo("https://api.tosspayments.com/v1/payments/pk_1/cancel"))
            .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON).body(error("ALREADY_CANCELED_PAYMENT")))

        assertThat(client.cancel("pk_1", "hold expired before approval", "comp:9")).isEqualTo(CancelResult.Cancelled(Instant.parse("2026-11-28T11:10:00Z")))
        assertThat(client.cancel("pk_1", "hold expired before approval", "comp:9")).isEqualTo(CancelResult.AlreadyCancelled)
    }

    @Test
    fun `M3_4 읽기 타임아웃과 연결 실패는 Unknown`() {
        val slow = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                Thread.sleep(2_000)
                exchange.sendResponseHeaders(200, -1)
                exchange.close()
            }
            start()
        }
        try {
            val factory = TossPgClient.requestFactory(Duration.ofMillis(500), Duration.ofMillis(300))
            val real = TossPgClient(RestClient.builder().requestFactory(factory), "test_sk_abc", "http://127.0.0.1:${slow.address.port}")
            assertThat(real.confirm("pk_1", "or-7-1", 150_000, "7:1")).isInstanceOf(ConfirmResult.Unknown::class.java)
            assertThat(real.inquire("or-7-1")).isInstanceOf(InquiryResult.Unknown::class.java)
        } finally {
            slow.stop(0)
        }
        val refused = TossPgClient(RestClient.builder().requestFactory(TossPgClient.requestFactory()), "test_sk_abc", "http://127.0.0.1:1")
        assertThat(refused.confirm("pk_1", "or-7-1", 150_000, "7:1")).isInstanceOf(ConfirmResult.Unknown::class.java)
    }
}
