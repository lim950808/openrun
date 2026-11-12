package dev.openrun.api

import dev.openrun.booking.queue.AdmissionScheduler
import dev.openrun.booking.queue.EnterOutcome
import dev.openrun.booking.queue.QueueIntegrityQuery
import dev.openrun.booking.queue.QueueService
import dev.openrun.booking.queue.QueueStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * M2-3 refresh storm in miniature: 200 members each enter and then poll 20 times concurrently (every fifth call a re-entry, i.e. a
 * page refresh) while the admission scheduler ticks. `rank_drift_count` = polls whose `ahead` grew versus the same
 * member's previous answer. Service-level, so the race is between Redis calls, not HTTP handling.
 */
@SpringBootTest
@Import(TestcontainersConfig::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QueueStormTest @Autowired constructor(
    private val admin: AdminController,
    private val queue: QueueService,
    private val scheduler: AdmissionScheduler,
    private val integrity: QueueIntegrityQuery,
) {
    private val pool = Executors.newFixedThreadPool(20)

    @AfterAll
    fun shutdown() {
        pool.shutdownNow()
    }

    /** What one call told the member: ahead, or -1 once admitted, or null when out of the queue. */
    private fun observe(showtimeId: Long, memberId: Long, openAt: Instant, refresh: Boolean): Long? =
        if (refresh) {
            when (val o = queue.enter(showtimeId, memberId, openAt)) {
                is EnterOutcome.Entered -> o.ahead
                is EnterOutcome.AlreadyAdmitted -> -1
                EnterOutcome.NotOpen -> null
            }
        } else {
            when (val s = queue.me(showtimeId, memberId)) {
                is QueueStatus.Waiting -> s.ahead
                is QueueStatus.Admitted -> -1
                QueueStatus.NotInQueue -> null
            }
        }

    @Test
    fun `M2_3 동시 폴링과 새로고침에도 순번이 커지지 않는다 (rank_drift_count 0)`() {
        val openAt = Instant.now().minusSeconds(1)
        val showtimeId = admin.newShowtime(openAt = openAt).showtimeId
        val memberIds = (1L..200L).map { showtimeId * 10_000 + it }

        val ticking = AtomicBoolean(true)
        val ticker = Thread {
            while (ticking.get()) {
                scheduler.tick()
                Thread.sleep(100)
            }
        }.apply { start() }
        val start = CountDownLatch(1)
        val futures = memberIds.map { member ->
            pool.submit<List<Long?>> {
                start.await()
                // Enter right before polling: a member that entered long before its first poll would be abandoned
                // (test abandon-after is 2 s) and re-enter at the back, which is by design, not rank drift.
                queue.enter(showtimeId, member, openAt)
                (0 until 20).map { i -> observe(showtimeId, member, openAt, refresh = i % 5 == 4) }
            }
        }
        start.countDown()
        val observations = futures.map { it.get(60, TimeUnit.SECONDS) }
        ticking.set(false)
        ticker.join()

        val rankDrift = observations.sumOf { seen ->
            seen.zipWithNext().count { (prev, next) ->
                // Waiting → larger ahead, or admitted → waiting again.
                prev != null && next != null && (if (prev == -1L) next != -1L else next != -1L && next > prev)
            }
        }
        assertThat(rankDrift).isZero()
        assertThat(observations.flatten()).doesNotContainNull()
        val q = integrity.check(showtimeId)
        assertThat(q.entered).isEqualTo(200)
        assertThat(q.waiting + q.admitted).isEqualTo(200)
        assertThat(q.admitted).isPositive()
        assertThat(q.fifoViolationRate).isEqualTo(0.0)
        assertThat(q.lost).isZero()
    }
}
