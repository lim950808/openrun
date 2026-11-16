package dev.openrun.booking.queue

import dev.openrun.booking.hold.toOffset
import dev.openrun.outbox.OutboxJdbcRepository
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.PreparedStatementSetter
import org.springframework.jdbc.core.RowCallbackHandler
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Per-showtime counts of one tick. Showtimes with nothing to do are absent. */
data class TickResult(val admitted: Map<Long, Int>, val abandoned: Map<Long, Int>)

/**
 * Admission (ADR-0006 §2): every tick, for each showtime that has opened, takes this tick's budget from the
 * showtime's Bucket4j bucket, reads the queue head (up to `rate * 5 + 1` members) and their last polls once, and
 * decides in memory from the front while budget remains. A member with no poll (or entry) for `abandon-after` is
 * abandoned without using budget; anyone else is admitted with an access token. Redis is updated with one Lua call
 * per showtime; the tick's rows and outbox events for a showtime are written in one transaction with batched SQL.
 * Phase times go to `openrun.queue.tick{phase}` (ADR-0006 §2-5).
 */
@Component
class AdmissionScheduler(
    private val redis: QueueRedis,
    private val showtimes: ShowtimeOpenAtJdbc,
    private val codec: TokenCodec,
    private val props: QueueProperties,
    private val jdbc: JdbcTemplate,
    private val tx: TransactionTemplate,
    private val outbox: OutboxJdbcRepository,
    private val registry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val buckets = ConcurrentHashMap<Long, AdmissionRateLimiter>()

    private sealed interface Decision {
        val memberId: Long
        val enteredAt: Instant
        val lastPolledAt: Instant?

        data class Admit(
            override val memberId: Long,
            override val enteredAt: Instant,
            override val lastPolledAt: Instant?,
            val token: String,
            val tokenExpiresAt: Instant,
        ) : Decision

        data class Abandon(
            override val memberId: Long,
            override val enteredAt: Instant,
            override val lastPolledAt: Instant?,
        ) : Decision
    }

    // fixedRate, not fixedDelay: a tick that takes 300 ms must not stretch the period, or admission_rate_actual
    // drops below the configured rate (first runner bench: 64/s for 100/s). The bucket refills by wall clock.
    @Scheduled(fixedRateString = "\${openrun.queue.tick-ms:1000}")
    @SchedulerLock(name = "queue-admission", lockAtMostFor = "1m", lockAtLeastFor = "500ms")
    fun scheduledTick() {
        tick()
    }

    fun tick(now: Instant = Instant.now()): TickResult {
        val started = System.nanoTime()
        try {
            val admitted = mutableMapOf<Long, Int>()
            val abandoned = mutableMapOf<Long, Int>()
            for (showtimeId in redis.openShowtimes()) {
                val openAt = showtimes.openAt(showtimeId) ?: continue
                if (openAt.isAfter(now)) continue
                val decisions = decide(showtimeId, now)
                if (decisions.isEmpty()) continue
                val writeStarted = System.nanoTime()
                write(showtimeId, decisions, now)
                record(PHASE_DB_WRITE, writeStarted)
                decisions.count { it is Decision.Admit }.takeIf { it > 0 }?.let { admitted[showtimeId] = it }
                decisions.count { it is Decision.Abandon }.takeIf { it > 0 }?.let { abandoned[showtimeId] = it }
            }
            if (admitted.isNotEmpty() || abandoned.isNotEmpty()) {
                log.debug("queue-admission admitted={} abandoned={}", admitted, abandoned)
            }
            return TickResult(admitted, abandoned)
        } finally {
            record(PHASE_TOTAL, started)
        }
    }

    /** This tick's decisions whose member was still in the queue (ZREM 1), in queue order. */
    private fun decide(showtimeId: Long, now: Instant): List<Decision> {
        val rate = props.admissionsPerSecond
        val bucket = buckets.computeIfAbsent(showtimeId) { AdmissionRateLimiter(rate) }
        var budget = bucket.takeUpTo(rate)
        if (budget == 0L) return emptyList()

        val readStarted = System.nanoTime()
        // Bounded so a queue full of abandoned members cannot keep one tick running.
        val candidates = redis.headCandidates(showtimeId, rate * 5 + 1)
        val polled = redis.lastPolledAll(showtimeId, candidates.map { it.memberId })
        record(PHASE_REDIS_READ, readStarted)

        val abandonBefore = now.minus(props.abandonAfter)
        val decisions = mutableListOf<Decision>()
        for (head in candidates) {
            if (budget == 0L) break
            val enteredAt = microsToInstant(head.enteredAtMicros)
            val lastPolled = polled[head.memberId]
            val lastActive = if (lastPolled != null && lastPolled.isAfter(enteredAt)) lastPolled else enteredAt
            if (lastActive.isBefore(abandonBefore)) {
                decisions += Decision.Abandon(head.memberId, enteredAt, lastPolled)
            } else {
                val token = codec.mintAccess(head.memberId, showtimeId)
                decisions += Decision.Admit(head.memberId, enteredAt, lastPolled, token.token, token.expiresAt)
                budget--
            }
        }
        if (decisions.isEmpty()) {
            bucket.refund(budget)
            return decisions
        }

        val abandons = decisions.filterIsInstance<Decision.Abandon>()
        val admits = decisions.filterIsInstance<Decision.Admit>()
        val writeStarted = System.nanoTime()
        val removed = redis.applyDecisions(
            showtimeId,
            abandons.map { it.memberId },
            admits.map { it.memberId to it.token },
            props.accessTokenTtl,
        )
        record(PHASE_REDIS_WRITE, writeStarted)

        val applied = (abandons + admits).zip(removed).filter { it.second }.map { it.first }.toSet()
        // An admit whose member had already left the queue did not use its budget.
        bucket.refund(budget + admits.count { it !in applied })
        return decisions.filter { it in applied }
    }

    private fun write(showtimeId: Long, decisions: List<Decision>, now: Instant) {
        tx.executeWithoutResult {
            val rowIds = openRowIds(showtimeId, decisions)
            val admits = decisions.filterIsInstance<Decision.Admit>()
            val abandons = decisions.filterIsInstance<Decision.Abandon>()

            // Taken in one statement and handed out ascending in queue order, so admitted_seq is monotonic within
            // the tick and across ticks.
            val seqs = if (admits.isEmpty()) {
                emptyList()
            } else {
                jdbc.queryForList(
                    "select nextval('queue_admission_admitted_seq') from generate_series(1, ?)",
                    Long::class.java,
                    admits.size,
                ).map { it!! }.sorted()
            }
            jdbc.batchUpdate(
                """
                update queue_admission
                set admitted_at = ?, admitted_seq = ?, token_expires_at = ?, last_polled_at = ?
                where id = ?
                """.trimIndent(),
                admits.mapIndexed { i, d ->
                    arrayOf<Any?>(
                        now.toOffset(), seqs[i], d.tokenExpiresAt.toOffset(), d.lastPolledAt?.toOffset(), rowIds.getValue(d.memberId),
                    )
                },
            )
            jdbc.batchUpdate(
                "update queue_admission set abandoned_at = ?, last_polled_at = ? where id = ?",
                abandons.map { d -> arrayOf<Any?>(now.toOffset(), d.lastPolledAt?.toOffset(), rowIds.getValue(d.memberId)) },
            )

            val seqByMember = admits.mapIndexed { i, d -> d.memberId to seqs[i] }.toMap()
            outbox.appendAll(
                decisions.map { d ->
                    val rowId = rowIds.getValue(d.memberId)
                    when (d) {
                        is Decision.Admit ->
                            QueueEvents.admitted(rowId, showtimeId, d.memberId, now, seqByMember.getValue(d.memberId), d.tokenExpiresAt)
                        is Decision.Abandon -> {
                            val lastActive = d.lastPolledAt?.takeIf { it.isAfter(d.enteredAt) } ?: d.enteredAt
                            QueueEvents.abandoned(rowId, showtimeId, d.memberId, now, lastActive)
                        }
                    }
                },
            )
        }
    }

    /**
     * memberId → the member's waiting row (latest entry), one query for the whole tick. A row is missing only when
     * the entry's DB insert failed after the ZADD and the client re-entered (no new row on re-entry); it is
     * recreated from the ZSET score so the decision still has a record.
     */
    private fun openRowIds(showtimeId: Long, decisions: List<Decision>): Map<Long, Long> {
        val rows = mutableMapOf<Long, Long>()
        jdbc.query(
            """
            select id, member_id from queue_admission
            where showtime_id = ? and member_id = any(?) and admitted_at is null and abandoned_at is null
            order by member_id, entered_at desc
            """.trimIndent(),
            PreparedStatementSetter { ps ->
                ps.setLong(1, showtimeId)
                ps.setArray(2, ps.connection.createArrayOf("bigint", decisions.map { it.memberId }.toTypedArray()))
            },
            RowCallbackHandler { rs -> rows.putIfAbsent(rs.getLong("member_id"), rs.getLong("id")) },
        )
        for (d in decisions) {
            if (d.memberId in rows) continue
            rows[d.memberId] = jdbc.queryForObject(
                "insert into queue_admission (showtime_id, member_id, entered_at) values (?, ?, ?) returning id",
                Long::class.java,
                showtimeId, d.memberId, d.enteredAt.toOffset(),
            )!!
        }
        return rows
    }

    private fun record(phase: String, startedNanos: Long) {
        Timer.builder(TICK_METRIC)
            .tag("phase", phase)
            .publishPercentiles(0.5, 0.99)
            .distributionStatisticExpiry(Duration.ofMinutes(10))
            .distributionStatisticBufferLength(1)
            .register(registry)
            .record(System.nanoTime() - startedNanos, TimeUnit.NANOSECONDS)
    }

    companion object {
        const val TICK_METRIC = "openrun.queue.tick"
        const val PHASE_REDIS_READ = "redis_read"
        const val PHASE_REDIS_WRITE = "redis_write"
        const val PHASE_DB_WRITE = "db_write"
        const val PHASE_TOTAL = "total"
    }
}
