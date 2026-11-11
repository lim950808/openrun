package dev.openrun.api

import dev.openrun.booking.catalog.SeatCountJdbc
import dev.openrun.booking.hold.RedisHoldScript
import dev.openrun.catalog.Seat
import dev.openrun.catalog.SeatGrade
import dev.openrun.catalog.SeatRepository
import dev.openrun.catalog.Show
import dev.openrun.catalog.ShowRepository
import dev.openrun.catalog.Venue
import dev.openrun.catalog.VenueRepository
import dev.openrun.outbox.OutboxEvent
import dev.openrun.outbox.OutboxJdbcRepository
import net.javacrumbs.shedlock.core.LockConfiguration
import net.javacrumbs.shedlock.core.LockProvider
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

/**
 * Release 0 gate (docs/roadmap.md "릴리스 0 게이트"): every dependency the roadmap needs works on Boot 4
 * against real Postgres and Redis containers. Each test name states the gate item it proves.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig::class)
class Boot4GateSmokeTest @Autowired constructor(
    private val jdbc: JdbcTemplate,
    private val venues: VenueRepository,
    private val shows: ShowRepository,
    private val seats: SeatRepository,
    private val seatCount: SeatCountJdbc,
    private val holdScript: RedisHoldScript,
    private val lockProvider: LockProvider,
    private val outbox: OutboxJdbcRepository,
    private val mvc: MockMvcTester,
) {
    @Test
    fun `gate (a) Flyway applied all migrations`() {
        val applied = jdbc.queryForObject(
            "select count(*) from flyway_schema_history where success",
            Long::class.java,
        )
        assertThat(applied).isGreaterThanOrEqualTo(3)
    }

    @Test
    fun `gate (b) Spring Data JPA + Hibernate save and read back with ddl-auto validate`() {
        val venue = venues.save(Venue(name = "Gate Hall"))
        val show = shows.save(Show(title = "연말 콘서트", venue = venue))

        val found = shows.findById(show.id!!).orElseThrow()

        assertThat(found.title).isEqualTo("연말 콘서트")
        assertThat(found.venue.id).isEqualTo(venue.id)
    }

    @Test
    fun `gate (c) JdbcTemplate sees rows written by JPA in a separate module`() {
        val venue = venues.save(Venue(name = "Count Hall"))
        assertThat(seatCount.countByVenue(venue.id!!)).isZero()

        seats.saveAll(
            listOf(
                Seat(venue = venue, grade = SeatGrade.VIP, rowLabel = "A", seatNo = 1),
                Seat(venue = venue, grade = SeatGrade.R, rowLabel = "B", seatNo = 1),
                Seat(venue = venue, grade = SeatGrade.S, rowLabel = "C", seatNo = 1),
            ),
        )

        assertThat(seatCount.countByVenue(venue.id!!)).isEqualTo(3)
        assertThat(seats.findAllByVenueId(venue.id!!)).hasSize(3)
    }

    @Test
    fun `gate (d) Spring Data Redis + Lettuce runs DefaultRedisScript SET NX PX`() {
        assertThat(holdScript.tryHold("seat:1", "m1", 5000)).isTrue()
        assertThat(holdScript.tryHold("seat:1", "m1", 5000)).isFalse()
        assertThat(holdScript.tryHold("seat:1", "m2", 5000)).isFalse()
    }

    @Test
    fun `gate (e) ShedLock JdbcTemplateLockProvider grants a lock once`() {
        val config = LockConfiguration(Instant.now(), "smoke", Duration.ofMinutes(1), Duration.ofMillis(100))

        val first = lockProvider.lock(config)
        assertThat(first).isPresent()
        assertThat(lockProvider.lock(config)).isEmpty()

        first.get().unlock()
    }

    @Test
    fun `gate (f) outbox append stores a jsonb payload`() {
        val event = OutboxEvent(
            aggregateType = "reservation",
            aggregateId = "r-1",
            eventType = "SmokeTested",
            payload = """{"seat":"A-1","amount":150000}""",
        )

        outbox.append(event)

        val row = jdbc.queryForMap(
            "select pg_typeof(payload)::text as type, payload ->> 'seat' as seat from outbox where event_id = ?",
            event.eventId,
        )
        assertThat(row["type"]).isEqualTo("jsonb")
        assertThat(row["seat"]).isEqualTo("A-1")
    }

    @Test
    fun `gate (g) WebMVC serves GET api ping`() {
        assertThat(mvc.get().uri("/api/ping"))
            .hasStatusOk()
            .bodyJson()
            .extractingPath("$.status").isEqualTo("ok")
    }
}
