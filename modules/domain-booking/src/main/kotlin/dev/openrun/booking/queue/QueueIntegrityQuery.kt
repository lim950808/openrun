package dev.openrun.booking.queue

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Instant

/** Queue verification numbers for one showtime. Names follow docs/metrics.md (M2). */
data class QueueIntegrity(
    /** Rows in `queue_admission` (one per entry; re-entry while waiting adds none). */
    val entered: Long,
    val admitted: Long,
    /** `abandoned_at` rows plus members still in the ZSET with no poll (or entry) for `abandon-after` (not written). */
    val abandoned: Long,
    /** ZCARD at check time. */
    val waiting: Long,
    /** `abandon_rate` = abandoned / entered; null when entered = 0. */
    val abandonRate: Double?,
    /** `fifo_violation_rate`: admitted rows with a later-entered row admitted earlier (smaller `admitted_seq`) / admitted. */
    val fifoViolationRate: Double?,
    /** `admission_delay_p50_s`, `admission_delay_p99_s`: admitted_at - greatest(entered_at, open_at). */
    val admissionDelayP50Seconds: Double?,
    val admissionDelayP99Seconds: Double?,
    /** `admission_rate_actual`: admitted / whole seconds spanned by admitted_at (first..last, inclusive). */
    val admissionRateActual: Double?,
    /** Waiting rows that are neither admitted, abandoned, nor in the ZSET (ADR-0006 §2-4). */
    val lost: Long,
)

@Repository
class QueueIntegrityQuery(
    private val jdbc: JdbcTemplate,
    private val redis: QueueRedis,
    private val props: QueueProperties,
) {
    fun check(showtimeId: Long): QueueIntegrity {
        val counts = jdbc.queryForMap(
            """
            select count(*) as entered,
                   count(*) filter (where admitted_at is not null) as admitted,
                   count(*) filter (where abandoned_at is not null) as abandoned
            from queue_admission where showtime_id = ?
            """.trimIndent(),
            showtimeId,
        )

        // For each admitted row, the smallest admitted_seq among rows that entered strictly later. A smaller value
        // than its own means someone behind it was admitted first.
        val fifo = jdbc.queryForObject(
            """
            select count(*) filter (where admitted_seq > later_min_seq) from (
                select admitted_seq,
                       min(admitted_seq) over (
                           order by entered_at desc
                           range between unbounded preceding and current row exclude group
                       ) as later_min_seq
                from queue_admission
                where showtime_id = ? and admitted_at is not null
            ) x
            """.trimIndent(),
            Long::class.java,
            showtimeId,
        ) ?: 0

        val delay = jdbc.queryForMap(
            """
            select percentile_cont(0.5) within group (order by d) as p50,
                   percentile_cont(0.99) within group (order by d) as p99,
                   count(*) as n,
                   floor(extract(epoch from max(admitted_at))) - floor(extract(epoch from min(admitted_at))) + 1 as span_s
            from (
                select q.admitted_at, extract(epoch from q.admitted_at - greatest(q.entered_at, s.open_at)) as d
                from queue_admission q join showtime s on s.id = q.showtime_id
                where q.showtime_id = ? and q.admitted_at is not null
            ) x
            """.trimIndent(),
            showtimeId,
        )

        val waitingMembers = redis.waitingMembers(showtimeId)
        val polled = redis.polledAll(showtimeId)
        val staleBefore = Instant.now().minus(props.abandonAfter)
        val staleWaiting = waitingMembers.count { (memberId, score) ->
            val entered = microsToInstant(score)
            val lastActive = polled[memberId]?.takeIf { it.isAfter(entered) } ?: entered
            lastActive.isBefore(staleBefore)
        }

        val openMembers = jdbc.queryForList(
            "select member_id from queue_admission where showtime_id = ? and admitted_at is null and abandoned_at is null",
            Long::class.java,
            showtimeId,
        )
        val lost = openMembers.count { it !in waitingMembers }

        val entered = (counts["entered"] as Number).toLong()
        val admitted = (counts["admitted"] as Number).toLong()
        val abandoned = (counts["abandoned"] as Number).toLong() + staleWaiting
        val spanSeconds = (delay["span_s"] as Number?)?.toDouble()
        return QueueIntegrity(
            entered = entered,
            admitted = admitted,
            abandoned = abandoned,
            waiting = redis.waitingCount(showtimeId),
            abandonRate = if (entered > 0) abandoned.toDouble() / entered else null,
            fifoViolationRate = if (admitted > 0) fifo.toDouble() / admitted else null,
            admissionDelayP50Seconds = (delay["p50"] as Number?)?.toDouble(),
            admissionDelayP99Seconds = (delay["p99"] as Number?)?.toDouble(),
            admissionRateActual = if (admitted > 0 && spanSeconds != null) admitted / spanSeconds else null,
            lost = lost.toLong(),
        )
    }
}
