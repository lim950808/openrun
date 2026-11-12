package dev.openrun.booking.queue

import dev.openrun.booking.hold.toOffset
import dev.openrun.outbox.OutboxJdbcRepository
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** Per-showtime counts of one tick. Showtimes with nothing to do are absent. */
data class TickResult(val admitted: Map<Long, Int>, val abandoned: Map<Long, Int>)

/**
 * Admission (ADR-0006 §2): every tick, for each showtime that has opened, takes this tick's budget from the
 * showtime's Bucket4j bucket and walks the queue head one member at a time. A member with no poll (or entry) for
 * `abandon-after` is abandoned without using budget; anyone else is admitted with an access token. Redis is
 * updated per member; the tick's rows and outbox events for a showtime are written in one transaction.
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
            val tokenExpiresAt: Instant,
        ) : Decision

        data class Abandon(
            override val memberId: Long,
            override val enteredAt: Instant,
            override val lastPolledAt: Instant?,
        ) : Decision
    }

    @Scheduled(fixedDelayString = "\${openrun.queue.tick-ms:1000}")
    @SchedulerLock(name = "queue-admission", lockAtMostFor = "1m", lockAtLeastFor = "500ms")
    fun scheduledTick() {
        tick()
    }

    fun tick(now: Instant = Instant.now()): TickResult {
        val admitted = mutableMapOf<Long, Int>()
        val abandoned = mutableMapOf<Long, Int>()
        for (showtimeId in redis.openShowtimes()) {
            val openAt = showtimes.openAt(showtimeId) ?: continue
            if (openAt.isAfter(now)) continue
            val decisions = decide(showtimeId, now)
            if (decisions.isEmpty()) continue
            write(showtimeId, decisions, now)
            decisions.count { it is Decision.Admit }.takeIf { it > 0 }?.let { admitted[showtimeId] = it }
            decisions.count { it is Decision.Abandon }.takeIf { it > 0 }?.let { abandoned[showtimeId] = it }
        }
        if (admitted.isNotEmpty() || abandoned.isNotEmpty()) {
            log.debug("queue-admission admitted={} abandoned={}", admitted, abandoned)
        }
        return TickResult(admitted, abandoned)
    }

    private fun decide(showtimeId: Long, now: Instant): List<Decision> {
        val rate = props.admissionsPerSecond
        val bucket = buckets.computeIfAbsent(showtimeId) { AdmissionRateLimiter(rate) }
        var budget = bucket.takeUpTo(rate)
        // Bounded so a queue full of abandoned members cannot keep one tick running.
        val maxLooks = rate * 5 + 1
        val abandonBefore = now.minus(props.abandonAfter)
        val decisions = mutableListOf<Decision>()
        var looks = 0L
        while (budget > 0 && looks < maxLooks) {
            val head = redis.head(showtimeId) ?: break
            looks++
            val enteredAt = microsToInstant(head.enteredAtMicros)
            val lastPolled = redis.lastPolled(showtimeId, head.memberId)
            val lastActive = if (lastPolled != null && lastPolled.isAfter(enteredAt)) lastPolled else enteredAt
            if (lastActive.isBefore(abandonBefore)) {
                if (redis.abandon(showtimeId, head.memberId)) decisions += Decision.Abandon(head.memberId, enteredAt, lastPolled)
            } else {
                val token = codec.mintAccess(head.memberId, showtimeId)
                if (redis.admit(showtimeId, head.memberId, token.token, props.accessTokenTtl)) {
                    decisions += Decision.Admit(head.memberId, enteredAt, lastPolled, token.expiresAt)
                    budget--
                }
            }
        }
        bucket.refund(budget)
        return decisions
    }

    private fun write(showtimeId: Long, decisions: List<Decision>, now: Instant) {
        tx.executeWithoutResult {
            for (d in decisions) {
                val rowId = openRowId(showtimeId, d)
                when (d) {
                    is Decision.Admit -> {
                        val seq = jdbc.queryForObject(
                            """
                            update queue_admission
                            set admitted_at = ?, admitted_seq = nextval('queue_admission_admitted_seq'),
                                token_expires_at = ?, last_polled_at = ?
                            where id = ?
                            returning admitted_seq
                            """.trimIndent(),
                            Long::class.java,
                            now.toOffset(), d.tokenExpiresAt.toOffset(), d.lastPolledAt?.toOffset(), rowId,
                        )!!
                        outbox.append(QueueEvents.admitted(rowId, showtimeId, d.memberId, now, seq, d.tokenExpiresAt))
                    }
                    is Decision.Abandon -> {
                        jdbc.update(
                            "update queue_admission set abandoned_at = ?, last_polled_at = ? where id = ?",
                            now.toOffset(), d.lastPolledAt?.toOffset(), rowId,
                        )
                        val lastActive = d.lastPolledAt?.takeIf { it.isAfter(d.enteredAt) } ?: d.enteredAt
                        outbox.append(QueueEvents.abandoned(rowId, showtimeId, d.memberId, now, lastActive))
                    }
                }
            }
        }
    }

    /**
     * The member's waiting row (latest entry). Missing only when the entry's DB insert failed after the ZADD and
     * the client re-entered (no new row on re-entry); the row is recreated from the ZSET score so the decision
     * still has a record.
     */
    private fun openRowId(showtimeId: Long, d: Decision): Long =
        jdbc.query(
            """
            select id from queue_admission
            where showtime_id = ? and member_id = ? and admitted_at is null and abandoned_at is null
            order by entered_at desc
            limit 1
            """.trimIndent(),
            { rs, _ -> rs.getLong("id") },
            showtimeId, d.memberId,
        ).singleOrNull()
            ?: jdbc.queryForObject(
                "insert into queue_admission (showtime_id, member_id, entered_at) values (?, ?, ?) returning id",
                Long::class.java,
                showtimeId, d.memberId, d.enteredAt.toOffset(),
            )!!
}
