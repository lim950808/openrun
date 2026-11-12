package dev.openrun.api

import com.jayway.jsonpath.JsonPath
import dev.openrun.booking.queue.AdmissionScheduler
import dev.openrun.booking.queue.QueueProperties
import dev.openrun.booking.queue.QueueRedis
import dev.openrun.booking.queue.TokenCodec
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.assertj.MockMvcTester
import org.springframework.test.web.servlet.assertj.MvcTestResult
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicLong

/** M2-1, M2-2, M2-4 through HTTP (ADR-0006 §4). Admission happens only when a test calls `tick()`. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig::class)
class QueueApiTest @Autowired constructor(
    private val mvc: MockMvcTester,
    private val admin: AdminController,
    private val scheduler: AdmissionScheduler,
    private val queueRedis: QueueRedis,
    private val redis: StringRedisTemplate,
    private val jdbc: JdbcTemplate,
    private val props: QueueProperties,
) {
    private val members = AtomicLong(System.nanoTime() % 1_000_000_000 + 2_000_000_000)

    private fun openShowtime() = admin.newShowtime(openAt = Instant.now().minusSeconds(1))

    /** Entry is allowed (within entry-lead) but admission has not started. */
    private fun upcomingShowtime() = admin.newShowtime(openAt = Instant.now().plus(Duration.ofMinutes(5)))

    private fun enter(showtimeId: Long, memberId: Long): MvcTestResult =
        mvc.post().uri("/api/showtimes/{id}/queue", showtimeId).header("X-Member-Id", memberId.toString()).exchange()

    private fun me(showtimeId: Long, visitorToken: String): MvcTestResult =
        mvc.get().uri("/api/showtimes/{id}/queue/me", showtimeId).header("Authorization", "Bearer $visitorToken").exchange()

    private fun hold(showtimeId: Long, authorization: String?, seatId: Long): MvcTestResult =
        mvc.post().uri("/api/showtimes/{id}/holds", showtimeId)
            .apply { if (authorization != null) header("Authorization", authorization) }
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"seatIds":[$seatId]}""")
            .exchange()

    private fun MvcTestResult.json(path: String): Any = JsonPath.read(response.contentAsString, path)

    private fun MvcTestResult.string(path: String): String = json(path) as String

    private fun MvcTestResult.long(path: String): Long = (json(path) as Number).toLong()

    private fun rows(showtimeId: Long, memberId: Long): Long =
        jdbc.queryForObject(
            "select count(*) from queue_admission where showtime_id = ? and member_id = ?",
            Long::class.java,
            showtimeId,
            memberId,
        )!!

    /** Enters and runs one tick; returns the access token from polling. */
    private fun admitted(showtimeId: Long, memberId: Long): String {
        val visitor = enter(showtimeId, memberId).string("$.visitorToken")
        scheduler.tick()
        val polled = me(showtimeId, visitor)
        assertThat(polled.string("$.status")).isEqualTo("ADMITTED")
        return polled.string("$.accessToken")
    }

    @Test
    fun `M2_1 진입하면 순번을 받고 먼저 들어온 회원이 앞이다`() {
        val showtime = upcomingShowtime()
        val (a, b) = members.incrementAndGet() to members.incrementAndGet()

        val first = enter(showtime.showtimeId, a)
        val second = enter(showtime.showtimeId, b)

        assertThat(first).hasStatus(HttpStatus.CREATED)
        assertThat(first.string("$.status")).isEqualTo("WAITING")
        assertThat(first.long("$.ahead")).isZero()
        assertThat(second).hasStatus(HttpStatus.CREATED)
        assertThat(second.long("$.ahead")).isEqualTo(1)
        assertThat(Instant.parse(first.string("$.enteredAt"))).isBefore(Instant.parse(second.string("$.enteredAt")))
        assertThat(rows(showtime.showtimeId, a)).isEqualTo(1)
        val events = jdbc.queryForList(
            """
            select o.event_type from outbox o join queue_admission q on o.aggregate_id = q.id::text
            where o.aggregate_type = 'queue_admission' and q.showtime_id = ? order by o.id
            """.trimIndent(),
            String::class.java,
            showtime.showtimeId,
        )
        assertThat(events).containsExactly("QueueEntered", "QueueEntered")
    }

    @Test
    fun `M2_1 폴링은 앞 인원 수를 돌려준다`() {
        val showtime = upcomingShowtime()
        val (a, b) = members.incrementAndGet() to members.incrementAndGet()
        val tokenA = enter(showtime.showtimeId, a).string("$.visitorToken")
        val tokenB = enter(showtime.showtimeId, b).string("$.visitorToken")

        val polledA = me(showtime.showtimeId, tokenA)
        val polledB = me(showtime.showtimeId, tokenB)

        assertThat(polledA).hasStatusOk()
        assertThat(polledA.string("$.status")).isEqualTo("WAITING")
        assertThat(polledA.long("$.ahead")).isZero()
        assertThat(polledB.long("$.ahead")).isEqualTo(1)
        assertThat(queueRedis.lastPolled(showtime.showtimeId, b)).isNotNull()
        assertThat(mvc.get().uri("/api/showtimes/{id}/queue/me", showtime.showtimeId))
            .hasStatus(HttpStatus.UNAUTHORIZED).bodyJson().extractingPath("$.code").isEqualTo("VISITOR_TOKEN_REQUIRED")
        // A visitor token is bound to its showtime.
        assertThat(me(showtime.showtimeId + 1, tokenA))
            .hasStatus(HttpStatus.UNAUTHORIZED).bodyJson().extractingPath("$.code").isEqualTo("VISITOR_TOKEN_INVALID")
    }

    @Test
    fun `M2_3 재진입은 같은 진입 시각과 순번을 유지한다`() {
        val showtime = upcomingShowtime()
        val (a, b) = members.incrementAndGet() to members.incrementAndGet()
        val first = enter(showtime.showtimeId, a)
        enter(showtime.showtimeId, b)

        val again = enter(showtime.showtimeId, a)

        assertThat(again).hasStatus(HttpStatus.OK)
        assertThat(again.string("$.enteredAt")).isEqualTo(first.string("$.enteredAt"))
        assertThat(again.long("$.ahead")).isZero()
        assertThat(rows(showtime.showtimeId, a)).isEqualTo(1)
    }

    @Test
    fun `M2_1 진입 허용 전에는 409`() {
        val showtime = admin.newShowtime(openAt = Instant.now().plus(Duration.ofHours(1)))

        assertThat(enter(showtime.showtimeId, members.incrementAndGet()))
            .hasStatus(HttpStatus.CONFLICT).bodyJson().extractingPath("$.code").isEqualTo("QUEUE_NOT_OPEN")
        assertThat(enter(Long.MAX_VALUE, members.incrementAndGet()))
            .hasStatus(HttpStatus.NOT_FOUND).bodyJson().extractingPath("$.code").isEqualTo("SHOWTIME_NOT_FOUND")
    }

    @Test
    fun `M2_2 입장 토큰 없이 좌석 API는 401`() {
        val showtime = openShowtime()
        val other = openShowtime()
        val member = members.incrementAndGet()
        val seat = showtime.seatIds.getValue("S").first()
        val visitor = enter(showtime.showtimeId, member).string("$.visitorToken")
        val otherShowtimeToken = admitted(other.showtimeId, member)
        val before = admin.queueIntegrity(showtime.showtimeId)["unauthorized_seat_calls"] as Long

        assertThat(hold(showtime.showtimeId, null, seat))
            .hasStatus(HttpStatus.UNAUTHORIZED).bodyJson().extractingPath("$.code").isEqualTo("ACCESS_TOKEN_REQUIRED")
        assertThat(mvc.get().uri("/api/showtimes/{id}/seats", showtime.showtimeId))
            .hasStatus(HttpStatus.UNAUTHORIZED).bodyJson().extractingPath("$.code").isEqualTo("ACCESS_TOKEN_REQUIRED")
        assertThat(hold(showtime.showtimeId, "Bearer $visitor", seat))
            .hasStatus(HttpStatus.UNAUTHORIZED).bodyJson().extractingPath("$.code").isEqualTo("ACCESS_TOKEN_INVALID")
        assertThat(hold(showtime.showtimeId, "Bearer $otherShowtimeToken", seat))
            .hasStatus(HttpStatus.UNAUTHORIZED).bodyJson().extractingPath("$.code").isEqualTo("ACCESS_TOKEN_INVALID")

        assertThat(admin.queueIntegrity(showtime.showtimeId)["unauthorized_seat_calls"]).isEqualTo(before + 4)
    }

    @Test
    fun `M2_2 입장 토큰 검증은 Redis 없이 된다`() {
        val showtime = openShowtime()
        val member = members.incrementAndGet()
        val accessToken = admitted(showtime.showtimeId, member)

        redis.connectionFactory!!.connection.use { it.serverCommands().flushAll() }
        assertThat(redis.keys("*")).isEmpty()

        val held = hold(showtime.showtimeId, "Bearer $accessToken", showtime.seatIds.getValue("VIP").first())
        assertThat(held).hasStatus(HttpStatus.CREATED)
        assertThat(held.string("$.strategy")).isEqualTo("CONDITIONAL_UPDATE")
        assertThat(mvc.get().uri("/api/showtimes/{id}/seats", showtime.showtimeId).header("Authorization", "Bearer $accessToken"))
            .hasStatusOk()
    }

    @Test
    fun `M2_4 만료 토큰으로 좌석 API는 401 ACCESS_TOKEN_EXPIRED`() {
        val showtime = openShowtime()
        val past = Clock.fixed(Instant.now().minus(props.accessTokenTtl).minusSeconds(60), ZoneOffset.UTC)
        val expired = TokenCodec(props.tokenSecret, props.visitorTokenTtl, props.accessTokenTtl, past)
            .mintAccess(members.incrementAndGet(), showtime.showtimeId)

        assertThat(hold(showtime.showtimeId, "Bearer ${expired.token}", showtime.seatIds.getValue("R").first()))
            .hasStatus(HttpStatus.UNAUTHORIZED).bodyJson().extractingPath("$.code").isEqualTo("ACCESS_TOKEN_EXPIRED")
    }

    @Test
    fun `M2_4 입장 상태에서 다시 진입하면 같은 입장 토큰을 돌려준다`() {
        val showtime = openShowtime()
        val member = members.incrementAndGet()
        val accessToken = admitted(showtime.showtimeId, member)

        val again = enter(showtime.showtimeId, member)

        assertThat(again).hasStatus(HttpStatus.OK)
        assertThat(again.string("$.status")).isEqualTo("ADMITTED")
        assertThat(again.string("$.accessToken")).isEqualTo(accessToken)
        assertThat(rows(showtime.showtimeId, member)).isEqualTo(1)
    }

    @Test
    fun `M2_4 입장 뒤 admitted 키가 사라지면 재진입은 새 순번이다`() {
        val showtime = openShowtime()
        val member = members.incrementAndGet()
        admitted(showtime.showtimeId, member)
        val other = enter(showtime.showtimeId, members.incrementAndGet())

        // What the 10-minute TTL does in production.
        redis.delete(queueRedis.admittedKey(showtime.showtimeId, member))
        val again = enter(showtime.showtimeId, member)

        assertThat(again).hasStatus(HttpStatus.CREATED)
        assertThat(again.string("$.status")).isEqualTo("WAITING")
        assertThat(again.long("$.ahead")).isEqualTo(1)
        // Back of the line: behind the member who entered while this one was admitted.
        assertThat(Instant.parse(again.string("$.enteredAt"))).isAfter(Instant.parse(other.string("$.enteredAt")))
        assertThat(rows(showtime.showtimeId, member)).isEqualTo(2)
    }
}
