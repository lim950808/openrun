package dev.openrun.api

import com.jayway.jsonpath.JsonPath
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.atomic.AtomicLong

/**
 * `openrun.queue.require-access-token=false` (ADR-0006 §3): the mode bench-m1 runs in. Seat APIs take `X-Member-Id`
 * as in M1. Separate context because only this property differs; real HTTP on a random port.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["openrun.queue.require-access-token=false"],
)
@Import(TestcontainersConfig::class)
class HoldLegacyIdentityTest @Autowired constructor(
    private val admin: AdminController,
    @LocalServerPort private val port: Int,
) {
    private val http = HttpClient.newHttpClient()
    private val members = AtomicLong(System.nanoTime() % 1_000_000_000 + 6_000_000_000)

    private fun send(request: HttpRequest.Builder): HttpResponse<String> =
        http.send(request.build(), HttpResponse.BodyHandlers.ofString())

    private fun hold(showtimeId: Long, memberId: Long?, seatId: Long): HttpResponse<String> =
        send(
            HttpRequest.newBuilder(URI.create("http://localhost:$port/api/showtimes/$showtimeId/holds"))
                .header("Content-Type", "application/json")
                .apply { if (memberId != null) header("X-Member-Id", memberId.toString()) }
                .POST(HttpRequest.BodyPublishers.ofString("""{"seatIds":[$seatId]}""")),
        )

    @Test
    fun `M1_1 입장 토큰 비활성 모드에서는 X-Member-Id로 hold한다`() {
        val showtime = admin.newShowtime()

        val result = hold(showtime.showtimeId, members.incrementAndGet(), showtime.seatIds.getValue("VIP").first())

        assertThat(result.statusCode()).isEqualTo(201)
        assertThat(JsonPath.read<Number>(result.body(), "$.reservationId").toLong()).isPositive()
    }

    @Test
    fun `M1_1 입장 토큰 비활성 모드에서는 X-Member-Id 없이 400 MISSING_HEADER`() {
        val showtime = admin.newShowtime()

        val result = hold(showtime.showtimeId, null, showtime.seatIds.getValue("S").first())

        assertThat(result.statusCode()).isEqualTo(400)
        assertThat(JsonPath.read<String>(result.body(), "$.code")).isEqualTo("MISSING_HEADER")
        val seats = send(HttpRequest.newBuilder(URI.create("http://localhost:$port/api/showtimes/${showtime.showtimeId}/seats")).GET())
        assertThat(seats.statusCode()).isEqualTo(200)
        assertThat(JsonPath.read<List<Any>>(seats.body(), "$")).hasSize(1500)
    }
}
