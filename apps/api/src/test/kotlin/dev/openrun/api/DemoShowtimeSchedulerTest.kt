package dev.openrun.api

import com.jayway.jsonpath.JsonPath
import dev.openrun.catalog.ShowtimeRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.assertj.MockMvcTester
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * ADR-0009 §4 with `openrun.demo.showtime-every=PT15M` (separate context: the shared one keeps the default PT0S so the
 * scheduler stays out of every other test). Ticks are driven by the tests; the scheduled tick is an hour away.
 */
@SpringBootTest(properties = ["openrun.demo.showtime-every=PT15M", "openrun.demo.tick-ms=3600000"])
@AutoConfigureMockMvc
@Import(TestcontainersConfig::class)
class DemoShowtimeSchedulerTest @Autowired constructor(
    private val scheduler: DemoShowtimeScheduler,
    private val provisioner: ShowtimeProvisioner,
    private val showtimes: ShowtimeRepository,
    private val jdbc: JdbcTemplate,
    private val mvc: MockMvcTester,
) {
    private fun Instant.toOffset(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)

    private fun showtimeIdsOpeningAt(openAt: Instant): List<Long> =
        jdbc.queryForList("select id from showtime where open_at = ? order by id", Long::class.java, openAt.toOffset()).map { it!! }

    private fun seatHoldCount(showtimeId: Long): Long =
        jdbc.queryForObject("select count(*) from seat_hold where showtime_id = ?", Long::class.java, showtimeId)!!

    @Test
    fun `M4 데모 회차 스케줄러는 다음 15분 경계에 열리는 회차를 하나 만들고 두 번째 틱은 만들지 않는다`() {
        val now = Instant.parse("2026-11-21T10:52:00Z")
        val boundary = Instant.parse("2026-11-21T11:00:00Z")
        assertThat(showtimeIdsOpeningAt(boundary)).isEmpty()

        val first = scheduler.tick(now) ?: error("first tick created nothing")
        val second = scheduler.tick(now)

        assertThat(second).isNull()
        assertThat(showtimeIdsOpeningAt(boundary)).containsExactly(first)
        val startsAt = jdbc.queryForObject("select starts_at from showtime where id = ?", OffsetDateTime::class.java, first)!!
        assertThat(startsAt.toInstant()).isEqualTo(boundary.plus(7, ChronoUnit.DAYS))
        assertThat(seatHoldCount(first)).isEqualTo(1_500L)

        // Same boundary from a later instant in the same slot, and the boundary itself moves on from an exact boundary.
        assertThat(scheduler.tick(Instant.parse("2026-11-21T10:59:59.999Z"))).isNull()
        assertThat(DemoShowtimeScheduler.nextBoundary(boundary, Duration.ofMinutes(15))).isEqualTo(Instant.parse("2026-11-21T11:15:00Z"))
        assertThat(DemoShowtimeScheduler.nextBoundary(Instant.parse("2026-11-21T11:14:00Z"), Duration.ofMinutes(15))).isEqualTo(Instant.parse("2026-11-21T11:15:00Z"))
    }

    @Test
    fun `M4 데모 회차 스케줄러는 PT0S이면 아무것도 만들지 않는다`() {
        val off = DemoShowtimeScheduler(DemoProperties(showtimeEvery = Duration.ZERO), showtimes, provisioner)
        val now = Instant.parse("2026-11-22T10:52:00Z")
        val boundary = Instant.parse("2026-11-22T11:00:00Z")

        assertThat(off.tick(now)).isNull()
        assertThat(off.tick(now)).isNull()

        assertThat(showtimeIdsOpeningAt(boundary)).isEmpty()
    }

    @Test
    fun `M4 공개 회차 목록은 가장 이른 3개만 돌려준다`() {
        // Opened long ago, not yet started: they sort before every showtime the other tests of this context create.
        val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val created = (1..4).map { i ->
            provisioner.create(startsAt = now.plus(1, ChronoUnit.DAYS).plus(i.toLong(), ChronoUnit.HOURS), openAt = now.minus(400 - i.toLong(), ChronoUnit.DAYS))
        }

        val res = mvc.get().uri("/api/showtimes").exchange()

        assertThat(res).hasStatusOk()
        val body = res.response.contentAsString
        val ids = JsonPath.read<List<Number>>(body, "$[*].showtimeId").map { it.toLong() }
        assertThat(ids).containsExactly(created[0].showtimeId, created[1].showtimeId, created[2].showtimeId)
        val openAts = JsonPath.read<List<String>>(body, "$[*].openAt").map(Instant::parse)
        assertThat(openAts).isSorted
    }
}
