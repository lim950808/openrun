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

/**
 * ADR-0010 §2: with the local default `server.forward-headers-strategy=none`, `X-Forwarded-For` must not become the
 * key, or a client without a proxy in front could dodge the `ip` policy by rotating the header. Separate context: the
 * only one where the rate limit is on and the strategy is `none`; every call comes from MockMvc's 127.0.0.1.
 */
@SpringBootTest(
    properties = [
        "openrun.ratelimit.enabled=true",
        "openrun.ratelimit.ip.per-second=1",
        "openrun.ratelimit.ip.burst=2",
        "server.forward-headers-strategy=none",
    ],
)
@AutoConfigureMockMvc
@Import(TestcontainersConfig::class)
class RateLimitForwardedHeadersOffTest @Autowired constructor(
    private val mvc: MockMvcTester,
) {
    @Test
    fun `P1_1 forward-headers-strategy가 none이면 X-Forwarded-For를 바꿔도 같은 IP 버킷이다`() {
        repeat(2) { i ->
            assertThat(mvc.get().uri("/api/showtimes").header("X-Forwarded-For", "10.20.0.$i").exchange()).hasStatusOk()
        }

        val rejected = mvc.get().uri("/api/showtimes").header("X-Forwarded-For", "10.20.0.99").exchange()

        assertThat(rejected).hasStatus(HttpStatus.TOO_MANY_REQUESTS)
        assertThat(JsonPath.read<String>(rejected.response.contentAsString, "$.code")).isEqualTo("RATE_LIMITED_IP")
    }
}
