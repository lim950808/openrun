package dev.openrun.api

import dev.openrun.booking.queue.AdmissionScheduler
import dev.openrun.booking.queue.EnterOutcome
import dev.openrun.booking.queue.QueueIntegrityQuery
import dev.openrun.booking.queue.QueueRedis
import dev.openrun.booking.queue.QueueService
import dev.openrun.booking.queue.QueueStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * ADR-0006 §2 with test properties: 5 admissions per second, abandon after 2 s. One showtime per test, so each test
 * has its own Bucket4j bucket. (`@AutoConfigureMockMvc` only to share the cached context with the HTTP tests.)
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig::class)
class AdmissionSchedulerTest @Autowired constructor(
    private val admin: AdminController,
    private val queue: QueueService,
    private val scheduler: AdmissionScheduler,
    private val queueRedis: QueueRedis,
    private val integrity: QueueIntegrityQuery,
    private val jdbc: JdbcTemplate,
) {
    private val members = AtomicLong(System.nanoTime() % 1_000_000_000 + 4_000_000_000)

    private fun showtime(openAt: Instant): Pair<Long, Instant> = admin.newShowtime(openAt = openAt).showtimeId to openAt

    private fun enterAll(showtime: Pair<Long, Instant>, count: Int): List<Long> =
        (1..count).map {
            val member = members.incrementAndGet()
            assertThat(queue.enter(showtime.first, member, showtime.second)).isInstanceOf(EnterOutcome.Entered::class.java)
            member
        }

    private fun admittedInOrder(showtimeId: Long): List<Long> =
        jdbc.queryForList(
            "select member_id from queue_admission where showtime_id = ? and admitted_at is not null order by admitted_seq",
            Long::class.java,
            showtimeId,
        ).filterNotNull()

    @Test
    fun `M2_2 오픈 전에는 입장하지 않는다`() {
        val st = showtime(Instant.now().plus(Duration.ofMinutes(5)))
        val entered = enterAll(st, 3)

        val result = scheduler.tick()

        assertThat(result.admitted[st.first]).isNull()
        assertThat(entered.map { queue.me(st.first, it) }).containsExactly(
            QueueStatus.Waiting(0),
            QueueStatus.Waiting(1),
            QueueStatus.Waiting(2),
        )
        assertThat(admittedInOrder(st.first)).isEmpty()
    }

    @Test
    fun `M2_2 틱마다 설정한 인원만 입장한다`() {
        val st = showtime(Instant.now().minusSeconds(1))
        val entered = enterAll(st, 12)

        val perTick = (1..3).map { round ->
            if (round > 1) {
                // Keep the waiting members alive (abandon-after is 2 s) and let the bucket refill.
                entered.forEach { queue.me(st.first, it) }
                Thread.sleep(1_100)
            }
            scheduler.tick().admitted[st.first] ?: 0
        }

        assertThat(perTick).containsExactly(5, 5, 2)
        assertThat(queueRedis.waitingCount(st.first)).isZero()
        assertThat(admittedInOrder(st.first)).isEqualTo(entered)
    }

    @Test
    fun `M2_2 입장 순서는 진입 순서다 (fifo_violation_rate 0)`() {
        val st = showtime(Instant.now().minusSeconds(1))
        val entered = enterAll(st, 5)

        scheduler.tick()

        assertThat(admittedInOrder(st.first)).isEqualTo(entered)
        val q = integrity.check(st.first)
        assertThat(q.admitted).isEqualTo(5)
        assertThat(q.fifoViolationRate).isEqualTo(0.0)
        assertThat(entered.map { queue.me(st.first, it) }).allMatch { it is QueueStatus.Admitted }
        val events = jdbc.query(
            """
            select o.event_type, count(*) from outbox o join queue_admission q on o.aggregate_id = q.id::text
            where o.aggregate_type = 'queue_admission' and q.showtime_id = ? group by o.event_type order by o.event_type
            """.trimIndent(),
            { rs, _ -> rs.getString(1) to rs.getLong(2) },
            st.first,
        )
        assertThat(events).containsExactly("QueueAdmitted" to 5L, "QueueEntered" to 5L)
    }

    @Test
    fun `M2 30초 무폴링 회원은 이탈 처리되고 예산을 쓰지 않는다`() {
        val st = showtime(Instant.now().minusSeconds(1))
        // Five silent members first, then five who keep polling. Budget is 5: if abandoning used budget, nobody
        // behind the silent ones would get in.
        val silent = enterAll(st, 5)
        val polling = enterAll(st, 5)
        repeat(3) {
            Thread.sleep(900)
            polling.forEach { queue.me(st.first, it) }
        }

        val result = scheduler.tick()

        assertThat(result.abandoned[st.first]).isEqualTo(5)
        assertThat(result.admitted[st.first]).isEqualTo(5)
        assertThat(admittedInOrder(st.first)).isEqualTo(polling)
        val abandonedRows = jdbc.queryForList(
            "select member_id from queue_admission where showtime_id = ? and abandoned_at is not null order by entered_at",
            Long::class.java,
            st.first,
        )
        assertThat(abandonedRows).isEqualTo(silent)
        assertThat(silent.map { queue.me(st.first, it) }).allMatch { it == QueueStatus.NotInQueue }
        val polledCopied = jdbc.queryForObject(
            "select count(*) from queue_admission where showtime_id = ? and admitted_at is not null and last_polled_at is not null",
            Long::class.java,
            st.first,
        )
        assertThat(polledCopied).isEqualTo(5)

        val q = integrity.check(st.first)
        assertThat(q.entered).isEqualTo(10)
        assertThat(q.admitted).isEqualTo(5)
        assertThat(q.abandoned).isEqualTo(5)
        assertThat(q.waiting).isZero()
        assertThat(q.lost).isZero()
        assertThat(q.abandonRate).isEqualTo(0.5)
        assertThat(q.fifoViolationRate).isEqualTo(0.0)
        assertThat(q.admissionRateActual).isEqualTo(5.0)
        assertThat(q.admissionDelayP50Seconds).isBetween(2.0, 10.0)
        assertThat(q.admissionDelayP99Seconds).isGreaterThanOrEqualTo(q.admissionDelayP50Seconds)
    }

    @Test
    fun `M2 점검 시점에 무폴링 대기자는 abandoned로 세고 아무도 입장 안 했으면 비율은 null`() {
        val st = showtime(Instant.now().plus(Duration.ofMinutes(5)))
        enterAll(st, 2)
        Thread.sleep(2_200)

        val q = integrity.check(st.first)

        assertThat(q.entered).isEqualTo(2)
        assertThat(q.waiting).isEqualTo(2)
        assertThat(q.abandoned).isEqualTo(2)
        assertThat(q.abandonRate).isEqualTo(1.0)
        assertThat(q.admitted).isZero()
        assertThat(q.fifoViolationRate).isNull()
        assertThat(q.admissionDelayP50Seconds).isNull()
        assertThat(q.admissionRateActual).isNull()
        // Counting does not write.
        assertThat(
            jdbc.queryForObject(
                "select count(*) from queue_admission where showtime_id = ? and abandoned_at is not null",
                Long::class.java,
                st.first,
            ),
        ).isZero()
    }

    @Test
    fun `M2 큐에서 빠졌는데 기록이 열려 있으면 lost로 센다`() {
        val st = showtime(Instant.now().plus(Duration.ofMinutes(5)))
        val (member) = enterAll(st, 1)

        // A tick that removed the member from Redis and then failed its DB transaction (ADR-0006 §2-4).
        queueRedis.abandon(st.first, member)

        assertThat(integrity.check(st.first).lost).isEqualTo(1)
        assertThat(queue.me(st.first, member)).isEqualTo(QueueStatus.NotInQueue)
    }
}
