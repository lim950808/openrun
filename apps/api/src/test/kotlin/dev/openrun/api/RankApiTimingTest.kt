package dev.openrun.api

import com.jayway.jsonpath.JsonPath
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.assertj.MockMvcTester
import org.springframework.test.web.servlet.assertj.MvcTestResult
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * `rank_api_server_p99_ms` (docs/metrics.md, ADR-0006 §2-5): the server-side time of `GET /queue/me` comes from Boot's
 * `http.server.requests` timer and is read per templated uri through the diagnostics endpoint. MockMvc runs the
 * servlet filter chain, so the observation filter records these requests like the embedded server would.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig::class)
class RankApiTimingTest @Autowired constructor(
    private val mvc: MockMvcTester,
    private val admin: AdminController,
) {
    private val members = AtomicLong(System.nanoTime() % 1_000_000_000 + 6_000_000_000)

    private fun MvcTestResult.json(path: String): Any? = JsonPath.read(response.contentAsString, path)

    private fun MvcTestResult.long(path: String): Long = (json(path) as Number).toLong()

    private fun httpRequests(reset: Boolean = false): MvcTestResult =
        mvc.get().uri("/api/admin/diagnostics/http-requests")
            .param("uri", RANK_URI)
            .param("reset", reset.toString())
            .exchange()

    @Test
    fun `M2_3 순번 API 서버 처리 시간은 http_server_requests 타이머로 읽는다`() {
        val showtime = admin.newShowtime(openAt = Instant.now().plus(Duration.ofMinutes(5)))
        val member = members.incrementAndGet()
        val entered = mvc.post().uri("/api/showtimes/{id}/queue", showtime.showtimeId)
            .header("X-Member-Id", member.toString())
            .exchange()
        assertThat(entered).hasStatus(HttpStatus.CREATED)
        val visitor = entered.json("$.visitorToken") as String

        val calls = 3
        repeat(calls) {
            assertThat(
                mvc.get().uri(RANK_URI, showtime.showtimeId).header("Authorization", "Bearer $visitor").exchange(),
            ).hasStatusOk()
        }

        val snapshot = httpRequests()
        assertThat(snapshot).hasStatusOk()
        assertThat(snapshot.json("$.uri")).isEqualTo(RANK_URI)
        assertThat(snapshot.long("$.count")).isGreaterThanOrEqualTo(calls.toLong())
        assertThat(snapshot.long("$.by_status['200'].count")).isGreaterThanOrEqualTo(calls.toLong())
        assertThat(snapshot.json("$.by_status['200'].p99_ms")).isNotNull()

        val reset = httpRequests(reset = true)
        assertThat(reset).hasStatusOk()
        assertThat(reset.long("$.count")).isGreaterThanOrEqualTo(calls.toLong())

        val after = httpRequests()
        assertThat(after).hasStatusOk()
        assertThat(after.long("$.count")).isZero()
        assertThat(after.json("$.by_status") as Map<*, *>).isEmpty()
    }

    companion object {
        const val RANK_URI = "/api/showtimes/{showtimeId}/queue/me"
    }
}
