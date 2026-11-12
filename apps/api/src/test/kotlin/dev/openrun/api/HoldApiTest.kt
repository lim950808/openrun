package dev.openrun.api

import com.jayway.jsonpath.JsonPath
import dev.openrun.booking.hold.SeatStatus
import dev.openrun.booking.hold.IntegrityQuery
import dev.openrun.booking.hold.SeatStatusQuery
import dev.openrun.booking.queue.TokenCodec
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
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig::class)
class HoldApiTest @Autowired constructor(
    private val mvc: MockMvcTester,
    private val admin: AdminController,
    private val seatStatus: SeatStatusQuery,
    private val registry: MeterRegistry,
    private val integrity: IntegrityQuery,
    private val codec: TokenCodec,
) {
    private val members = AtomicLong(System.nanoTime() % 1_000_000_000)

    private fun hold(showtimeId: Long, memberId: Long, vararg seatIds: Long): MvcTestResult =
        mvc.post().uri("/api/showtimes/{id}/holds", showtimeId)
            .header("Authorization", codec.bearerFor(showtimeId, memberId))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"seatIds":${seatIds.toList()}}""")
            .exchange()

    private fun statusOf(showtimeId: Long, seatId: Long): SeatStatus =
        seatStatus.seats(showtimeId).single { it.seatId == seatId }.status

    @Test
    fun `M1_1 두 좌석 hold는 201과 만료 시각을 돌려준다`() {
        val showtime = admin.newShowtime()
        val (a, b) = showtime.seatIds.getValue("VIP")
        val before = Instant.now()

        val result = hold(showtime.showtimeId, members.incrementAndGet(), b, a)

        assertThat(result).hasStatus(HttpStatus.CREATED)
        val body = result.response.contentAsString
        val expiresAt = Instant.parse(JsonPath.read<String>(body, "$.expiresAt"))
        assertThat(Duration.between(before, expiresAt)).isBetween(Duration.ofMinutes(11), Duration.ofMinutes(13))
        assertThat(JsonPath.read<List<Int>>(body, "$.seats[*].seatId").map { it.toLong() }).containsExactly(a, b)
        assertThat(JsonPath.read<Int>(body, "$.totalAmount")).isEqualTo(300_000)
        assertThat(JsonPath.read<String>(body, "$.strategy")).isEqualTo("CONDITIONAL_UPDATE")
    }

    @Test
    fun `M1_4 활성 예약이 있으면 409 ACTIVE_RESERVATION_EXISTS`() {
        val showtime = admin.newShowtime()
        val (a, b) = showtime.seatIds.getValue("R")
        val member = members.incrementAndGet()
        assertThat(hold(showtime.showtimeId, member, a)).hasStatus(HttpStatus.CREATED)

        assertThat(hold(showtime.showtimeId, member, b))
            .hasStatus(HttpStatus.CONFLICT)
            .bodyJson().extractingPath("$.code").isEqualTo("ACTIVE_RESERVATION_EXISTS")
        assertThat(statusOf(showtime.showtimeId, a)).isEqualTo(SeatStatus.HELD)
        assertThat(statusOf(showtime.showtimeId, b)).isEqualTo(SeatStatus.AVAILABLE)
    }

    @Test
    fun `좌석 3개는 400 INVALID_SEAT_COUNT`() {
        val showtime = admin.newShowtime()
        val (a, b, c) = showtime.seatIds.getValue("S")

        assertThat(hold(showtime.showtimeId, members.incrementAndGet(), a, b, c))
            .hasStatus(HttpStatus.BAD_REQUEST)
            .bodyJson().extractingPath("$.code").isEqualTo("INVALID_SEAT_COUNT")
    }

    @Test
    fun `M2_2 입장 토큰 모드에서 X-Member-Id만 보내면 401 ACCESS_TOKEN_REQUIRED`() {
        val showtime = admin.newShowtime()

        assertThat(
            mvc.post().uri("/api/showtimes/{id}/holds", showtime.showtimeId)
                .header("X-Member-Id", members.incrementAndGet().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"seatIds":[${showtime.seatIds.getValue("S").first()}]}"""),
        ).hasStatus(HttpStatus.UNAUTHORIZED)
            .bodyJson().extractingPath("$.code").isEqualTo("ACCESS_TOKEN_REQUIRED")
    }

    @Test
    fun `DELETE는 X-Member-Id도 입장 토큰도 없으면 400`() {
        assertThat(mvc.delete().uri("/api/reservations/{id}", 1)).hasStatus(HttpStatus.BAD_REQUEST)
            .bodyJson().extractingPath("$.code").isEqualTo("MISSING_HEADER")
    }

    @Test
    fun `M2_2 DELETE는 입장 토큰으로도 소유자를 확인한다`() {
        val showtime = admin.newShowtime()
        val owner = members.incrementAndGet()
        val held = hold(showtime.showtimeId, owner, showtime.seatIds.getValue("S")[5])
        val reservationId = JsonPath.read<Number>(held.response.contentAsString, "$.reservationId").toLong()

        assertThat(
            mvc.delete().uri("/api/reservations/{id}", reservationId).header("Authorization", codec.bearerFor(showtime.showtimeId, owner)),
        ).hasStatus(HttpStatus.NO_CONTENT)
    }

    @Test
    fun `같은 좌석을 다른 회원이 잡으면 409 SEAT_TAKEN`() {
        val showtime = admin.newShowtime()
        val (a, b) = showtime.seatIds.getValue("VIP")
        assertThat(hold(showtime.showtimeId, members.incrementAndGet(), a)).hasStatus(HttpStatus.CREATED)
        val takenBefore = registry.find("openrun.api.errors").tag("code", "SEAT_TAKEN").counter()?.count() ?: 0.0

        assertThat(hold(showtime.showtimeId, members.incrementAndGet(), b, a))
            .hasStatus(HttpStatus.CONFLICT)
            .bodyJson().extractingPath("$.code").isEqualTo("SEAT_TAKEN")
        assertThat(registry.get("openrun.api.errors").tag("code", "SEAT_TAKEN").counter().count()).isEqualTo(takenBefore + 1)
        // The partial claim on b was rolled back with the rest of the transaction.
        assertThat(statusOf(showtime.showtimeId, b)).isEqualTo(SeatStatus.AVAILABLE)
    }

    @Test
    fun `DELETE 후 좌석은 AVAILABLE로 돌아오고 다시 잡을 수 있다`() {
        val showtime = admin.newShowtime()
        val a = showtime.seatIds.getValue("R").first()
        val owner = members.incrementAndGet()
        val held = hold(showtime.showtimeId, owner, a)
        val reservationId = JsonPath.read<Number>(held.response.contentAsString, "$.reservationId").toLong()

        assertThat(mvc.delete().uri("/api/reservations/{id}", reservationId).header("X-Member-Id", (owner + 1).toString()))
            .hasStatus(HttpStatus.CONFLICT)
            .bodyJson().extractingPath("$.code").isEqualTo("ILLEGAL_TRANSITION")
        assertThat(mvc.delete().uri("/api/reservations/{id}", reservationId).header("X-Member-Id", owner.toString()))
            .hasStatus(HttpStatus.NO_CONTENT)

        assertThat(statusOf(showtime.showtimeId, a)).isEqualTo(SeatStatus.AVAILABLE)
        assertThat(hold(showtime.showtimeId, members.incrementAndGet(), a)).hasStatus(HttpStatus.CREATED)
        // Cancel then re-hold is sequential, not an overlap.
        assertThat(integrity.check(showtime.showtimeId).oversellOccurredCount).isZero()
        assertThat(mvc.delete().uri("/api/reservations/{id}", reservationId).header("X-Member-Id", owner.toString()))
            .hasStatus(HttpStatus.CONFLICT)
    }

    @Test
    fun `GET seats는 HELD 상태를 보여준다`() {
        val showtime = admin.newShowtime()
        val a = showtime.seatIds.getValue("S").last()
        assertThat(hold(showtime.showtimeId, members.incrementAndGet(), a)).hasStatus(HttpStatus.CREATED)

        val result = mvc.get().uri("/api/showtimes/{id}/seats", showtime.showtimeId)
            .header("Authorization", codec.bearerFor(showtime.showtimeId, members.incrementAndGet()))
            .exchange()

        assertThat(result).hasStatusOk()
        val body = result.response.contentAsString
        assertThat(JsonPath.read<List<Any>>(body, "$")).hasSize(1500)
        assertThat(JsonPath.read<List<String>>(body, "$[?(@.seatId == $a)].status")).containsExactly("HELD")
        assertThat(JsonPath.read<List<String>>(body, "$[?(@.status == 'HELD')].status")).hasSize(1)
    }
}
