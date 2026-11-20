package dev.openrun.api

import com.jayway.jsonpath.JsonPath
import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.assertj.MockMvcTester
import org.springframework.test.web.servlet.assertj.MvcTestResult
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * ADR-0009 §3 with `openrun.admin.token` set (separate context: every other test runs with it blank, the open mode
 * the runners and CI use, and must keep passing unchanged).
 */
@SpringBootTest(properties = ["openrun.admin.token=$TOKEN"])
@AutoConfigureMockMvc
@Import(TestcontainersConfig::class)
class AdminTokenTest @Autowired constructor(
    private val mvc: MockMvcTester,
    private val registry: MeterRegistry,
) {
    private fun createShowtime(token: String?): MvcTestResult {
        val openAt = Instant.now().plus(1, ChronoUnit.DAYS)
        return mvc.post().uri("/api/admin/showtimes")
            .apply { if (token != null) header(AdminTokenInterceptor.HEADER, token) }
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"startsAt":"${openAt.plus(7, ChronoUnit.DAYS)}","openAt":"$openAt"}""")
            .exchange()
    }

    private fun MvcTestResult.code(): String = JsonPath.read(response.contentAsString, "$.code")

    private fun errorCount(code: String): Double =
        registry.find("openrun.api.errors").tag("code", code).counter()?.count() ?: 0.0

    @Test
    fun `M4 관리자 토큰이 설정되면 X-Admin-Token 없는 admin API 호출은 401 ADMIN_TOKEN_REQUIRED`() {
        val before = errorCount("ADMIN_TOKEN_REQUIRED")

        val res = createShowtime(token = null)

        assertThat(res).hasStatus(HttpStatus.UNAUTHORIZED)
        assertThat(res.code()).isEqualTo("ADMIN_TOKEN_REQUIRED")
        assertThat(errorCount("ADMIN_TOKEN_REQUIRED")).isEqualTo(before + 1)

        val blank = mvc.get().uri("/api/admin/diagnostics/queue-ticks").header(AdminTokenInterceptor.HEADER, " ").exchange()
        assertThat(blank).hasStatus(HttpStatus.UNAUTHORIZED)
        assertThat(blank.code()).isEqualTo("ADMIN_TOKEN_REQUIRED")
    }

    @Test
    fun `M4 관리자 토큰이 설정되면 틀린 토큰은 401 ADMIN_TOKEN_INVALID`() {
        val before = errorCount("ADMIN_TOKEN_INVALID")

        val res = createShowtime(token = "not-the-token")

        assertThat(res).hasStatus(HttpStatus.UNAUTHORIZED)
        assertThat(res.code()).isEqualTo("ADMIN_TOKEN_INVALID")
        assertThat(errorCount("ADMIN_TOKEN_INVALID")).isEqualTo(before + 1)

        val prefix = createShowtime(token = TOKEN.dropLast(1))
        assertThat(prefix).hasStatus(HttpStatus.UNAUTHORIZED)
        assertThat(prefix.code()).isEqualTo("ADMIN_TOKEN_INVALID")
    }

    @Test
    fun `M4 관리자 토큰이 설정되면 맞는 토큰은 통과한다`() {
        val created = createShowtime(token = TOKEN)
        assertThat(created).hasStatus(HttpStatus.CREATED)
        assertThat(JsonPath.read<Number>(created.response.contentAsString, "$.totalSeats").toInt()).isEqualTo(1_500)

        val diagnostics = mvc.get().uri("/api/admin/diagnostics/queue-ticks").header(AdminTokenInterceptor.HEADER, TOKEN).exchange()
        assertThat(diagnostics).hasStatusOk()
    }

    @Test
    fun `M4 관리자 토큰이 설정되어도 공개 API는 토큰 없이 그대로다`() {
        assertThat(mvc.get().uri("/api/showtimes").exchange()).hasStatusOk()
        assertThat(mvc.get().uri("/api/ping").exchange()).hasStatusOk()
    }
}

private const val TOKEN = "test-admin-token"
