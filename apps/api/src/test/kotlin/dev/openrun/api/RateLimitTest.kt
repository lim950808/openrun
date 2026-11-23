package dev.openrun.api

import com.jayway.jsonpath.JsonPath
import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.assertj.MockMvcTester
import org.springframework.test.web.servlet.assertj.MvcTestResult
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * ADR-0010 with the rate limit on (separate context: every other test runs with it off, as the runners and CI do).
 * Small limits so a test trips them in a few calls. `framework` strategy: MockMvc runs Boot's `ForwardedHeaderFilter`,
 * so each test picks its own client IP through `X-Forwarded-For` and the shared buckets never interfere.
 */
@SpringBootTest(
    properties = [
        "openrun.ratelimit.enabled=true",
        "openrun.ratelimit.ip.per-second=1",
        "openrun.ratelimit.ip.burst=5",
        "openrun.ratelimit.caller.per-second=1",
        "openrun.ratelimit.caller.burst=2",
        "server.forward-headers-strategy=framework",
    ],
)
@AutoConfigureMockMvc
@Import(TestcontainersConfig::class)
class RateLimitTest @Autowired constructor(
    private val mvc: MockMvcTester,
    private val registry: MeterRegistry,
    private val admin: AdminController,
) {
    private val members = AtomicLong(System.nanoTime() % 1_000_000_000 + 7_000_000_000)

    private fun newAddress(): String = addresses.incrementAndGet().let { "10.10.${it / 256}.${it % 256}" }

    private fun MvcTestResult.code(): String = JsonPath.read(response.contentAsString, "$.code")

    private fun errorCount(code: String): Double =
        registry.find("openrun.api.errors").tag("code", code).counter()?.count() ?: 0.0

    private fun showtimes(address: String): MvcTestResult =
        mvc.get().uri("/api/showtimes").header(FORWARDED_FOR, address).exchange()

    @Test
    fun `P1_1 IP 한도를 넘긴 요청은 429 RATE_LIMITED_IP와 Retry-After를 받고 카운터가 오른다`() {
        val address = newAddress()
        val before = errorCount("RATE_LIMITED_IP")

        repeat(5) { assertThat(showtimes(address)).hasStatusOk() }
        val rejected = showtimes(address)

        assertThat(rejected).hasStatus(HttpStatus.TOO_MANY_REQUESTS)
        assertThat(rejected.code()).isEqualTo("RATE_LIMITED_IP")
        assertThat(rejected.response.getHeader(HttpHeaders.RETRY_AFTER)!!.toLong()).isGreaterThanOrEqualTo(1)
        assertThat(errorCount("RATE_LIMITED_IP")).isEqualTo(before + 1)
        assertThat(showtimes(newAddress())).hasStatusOk()
    }

    @Test
    fun `P1_1 대기열 폴링은 토큰당 버스트를 넘기면 429 RATE_LIMITED_CALLER, 다른 토큰은 영향 없음`() {
        val showtime = admin.newShowtime(openAt = Instant.now().plus(Duration.ofMinutes(5)))
        fun enter(memberId: Long): String =
            mvc.post().uri("/api/showtimes/{id}/queue", showtime.showtimeId)
                .header(FORWARDED_FOR, newAddress())
                .header("X-Member-Id", memberId.toString())
                .exchange()
                .let { JsonPath.read(it.response.contentAsString, "$.visitorToken") }
        fun me(visitorToken: String): MvcTestResult =
            mvc.get().uri("/api/showtimes/{id}/queue/me", showtime.showtimeId)
                .header(FORWARDED_FOR, newAddress())
                .header(HttpHeaders.AUTHORIZATION, "Bearer $visitorToken")
                .exchange()
        val a = enter(members.incrementAndGet())
        val b = enter(members.incrementAndGet())
        val before = errorCount("RATE_LIMITED_CALLER")

        repeat(2) { assertThat(me(a)).hasStatusOk() }
        val rejected = me(a)

        assertThat(rejected).hasStatus(HttpStatus.TOO_MANY_REQUESTS)
        assertThat(rejected.code()).isEqualTo("RATE_LIMITED_CALLER")
        assertThat(rejected.response.getHeader(HttpHeaders.RETRY_AFTER)!!.toLong()).isGreaterThanOrEqualTo(1)
        assertThat(errorCount("RATE_LIMITED_CALLER")).isEqualTo(before + 1)
        assertThat(me(b)).hasStatusOk()
    }

    @Test
    fun `P1_1 관리자·웹훅·ping 경로는 한도 밖`() {
        val address = newAddress()

        repeat(10) {
            assertThat(mvc.get().uri("/api/ping").header(FORWARDED_FOR, address).exchange()).hasStatusOk()
            assertThat(mvc.get().uri("/api/admin/diagnostics/queue-ticks").header(FORWARDED_FOR, address).exchange()).hasStatusOk()
            val webhook = mvc.post().uri("/api/webhooks/toss")
                .header(FORWARDED_FOR, address)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .exchange()
            assertThat(webhook.response.status).isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS.value())
        }
        // The address's ip bucket was never touched: a full burst of public calls still passes.
        repeat(5) { assertThat(showtimes(address)).hasStatusOk() }
    }
}

private const val FORWARDED_FOR = "X-Forwarded-For"

/** Top level, not per instance: JUnit makes a new instance per test while the buckets live as long as the context. */
private val addresses = AtomicInteger()
