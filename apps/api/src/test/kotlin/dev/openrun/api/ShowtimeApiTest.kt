package dev.openrun.api

import com.jayway.jsonpath.JsonPath
import dev.openrun.booking.queue.QueueProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.assertj.MockMvcTester
import java.time.Instant
import java.time.temporal.ChronoUnit

/** M4-1 public showtime lookup for the web home and queue pages (ADR-0008 §1). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig::class)
class ShowtimeApiTest @Autowired constructor(
    private val mvc: MockMvcTester,
    private val admin: AdminController,
    private val props: QueueProperties,
) {
    /**
     * The list is capped at the 3 earliest by `openAt` (ADR-0009 §4) and the shared context holds showtimes from other
     * tests (`openAt` no earlier than a few minutes ago), so these two open days in the past to land at the front.
     */
    @Test
    fun `M4_1 회차 목록은 시작 전 회차를 오픈 시각 순으로 보여 주고 지난 회차는 뺀다`() {
        val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val later = admin.createShowtime(CreateShowtimeRequest(startsAt = now.plus(8, ChronoUnit.DAYS), openAt = now.minus(29, ChronoUnit.DAYS)))
        val sooner = admin.createShowtime(CreateShowtimeRequest(startsAt = now.plus(7, ChronoUnit.DAYS), openAt = now.minus(30, ChronoUnit.DAYS)))
        val past = admin.createShowtime(CreateShowtimeRequest(startsAt = now.minus(1, ChronoUnit.HOURS), openAt = now.minus(31, ChronoUnit.DAYS)))

        val res = mvc.get().uri("/api/showtimes").exchange()

        assertThat(res).hasStatusOk()
        val body = res.response.contentAsString
        val ids = JsonPath.read<List<Number>>(body, "$[*].showtimeId").map { it.toLong() }
        assertThat(ids).contains(later.showtimeId, sooner.showtimeId).doesNotContain(past.showtimeId)
        assertThat(ids.indexOf(sooner.showtimeId)).isLessThan(ids.indexOf(later.showtimeId))
        val openAts = JsonPath.read<List<String>>(body, "$[*].openAt").map(Instant::parse)
        assertThat(openAts).isSorted
    }

    @Test
    fun `M4_1 회차 상세는 공연명 공연장 좌석 수와 진입 허용 시각을 준다`() {
        val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val openAt = now.plus(1, ChronoUnit.DAYS)
        val startsAt = now.plus(7, ChronoUnit.DAYS)
        val created = admin.createShowtime(CreateShowtimeRequest(startsAt = startsAt, openAt = openAt))

        val res = mvc.get().uri("/api/showtimes/{id}", created.showtimeId).exchange()

        assertThat(res).hasStatusOk()
        val body = res.response.contentAsString
        assertThat(JsonPath.read<Number>(body, "$.showtimeId").toLong()).isEqualTo(created.showtimeId)
        assertThat(JsonPath.read<String>(body, "$.showTitle")).isEqualTo(DevSeeder.SHOW_TITLE)
        assertThat(JsonPath.read<String>(body, "$.venueName")).isEqualTo(DevSeeder.VENUE_NAME)
        assertThat(Instant.parse(JsonPath.read(body, "$.startsAt"))).isEqualTo(startsAt)
        assertThat(Instant.parse(JsonPath.read(body, "$.openAt"))).isEqualTo(openAt)
        assertThat(Instant.parse(JsonPath.read(body, "$.entryOpensAt"))).isEqualTo(openAt.minus(props.entryLead))
        assertThat(JsonPath.read<Number>(body, "$.totalSeats").toLong()).isEqualTo(1_500L)
    }

    @Test
    fun `M4_1 없는 회차 상세는 404 SHOWTIME_NOT_FOUND`() {
        val res = mvc.get().uri("/api/showtimes/{id}", Long.MAX_VALUE).exchange()

        assertThat(res).hasStatus(HttpStatus.NOT_FOUND)
        assertThat(JsonPath.read<String>(res.response.contentAsString, "$.code")).isEqualTo("SHOWTIME_NOT_FOUND")
    }
}
