package dev.openrun.booking.queue

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

/** Result of [QueueRedis.enqueue]: the score actually stored (existing on re-entry) and whether it was new. */
data class Enqueued(val enteredAtMicros: Long, val newlyAdded: Boolean) {
    val enteredAt: Instant get() = microsToInstant(enteredAtMicros)
}

/** Head of a showtime's queue: the member with the lowest score. */
data class QueueHead(val memberId: Long, val enteredAtMicros: Long)

fun microsToInstant(micros: Long): Instant = Instant.ofEpochSecond(micros / 1_000_000, (micros % 1_000_000) * 1_000)

/**
 * Redis side of the waiting queue (ADR-0006 §1). Keys use `{showtimeId}` as a cluster hash tag so one showtime's
 * queue, poll hash and admitted keys share a slot. `queue:showtimes` is global (single-node Redis in 1차).
 */
@Component
class QueueRedis(
    private val redis: StringRedisTemplate,
) {
    // ZADD NX with the Redis server clock as score (microseconds), so re-entry keeps its rank (M2-3) and every
    // app instance shares one clock. The score is built as a string: Lua 5.1 tostring() would print 16 digits
    // in exponent form.
    private val enqueueScript = DefaultRedisScript(
        """
        local existing = redis.call('ZSCORE', KEYS[1], ARGV[1])
        if existing then return {existing, '0'} end
        local t = redis.call('TIME')
        local score = t[1] .. string.format('%06d', tonumber(t[2]))
        redis.call('ZADD', KEYS[1], 'NX', score, ARGV[1])
        redis.call('SADD', KEYS[2], ARGV[2])
        return {score, '1'}
        """.trimIndent(),
        List::class.java,
    )

    // Removes the head and records the admission in one step, so a poll never sees a member that is neither
    // waiting nor admitted. Returns 0 when the member was no longer in the queue.
    private val admitScript = DefaultRedisScript(
        """
        if redis.call('ZREM', KEYS[1], ARGV[1]) == 0 then return 0 end
        redis.call('HDEL', KEYS[2], ARGV[1])
        redis.call('SET', KEYS[3], ARGV[2], 'EX', ARGV[3])
        return 1
        """.trimIndent(),
        Long::class.java,
    )

    private val abandonScript = DefaultRedisScript(
        """
        local n = redis.call('ZREM', KEYS[1], ARGV[1])
        redis.call('HDEL', KEYS[2], ARGV[1])
        return n
        """.trimIndent(),
        Long::class.java,
    )

    fun queueKey(showtimeId: Long) = "queue:{$showtimeId}"

    fun polledKey(showtimeId: Long) = "queue:{$showtimeId}:polled"

    fun admittedKey(showtimeId: Long, memberId: Long) = "admitted:{$showtimeId}:$memberId"

    fun enqueue(showtimeId: Long, memberId: Long): Enqueued {
        val result = redis.execute(enqueueScript, listOf(queueKey(showtimeId), SHOWTIMES_KEY), memberId.toString(), showtimeId.toString())
        val (score, added) = result.map { it.toString() }
        return Enqueued(score.toBigDecimal().toLong(), added == "1")
    }

    /** `ZRANK`, 0-based; null when the member is not waiting. */
    fun rank(showtimeId: Long, memberId: Long): Long? = redis.opsForZSet().rank(queueKey(showtimeId), memberId.toString())

    fun recordPoll(showtimeId: Long, memberId: Long, at: Instant) {
        redis.opsForHash<String, String>().put(polledKey(showtimeId), memberId.toString(), at.toEpochMilli().toString())
    }

    fun lastPolled(showtimeId: Long, memberId: Long): Instant? =
        redis.opsForHash<String, String>().get(polledKey(showtimeId), memberId.toString())?.let { Instant.ofEpochMilli(it.toLong()) }

    /** Lowest-score member without removing it. Only the single admission scheduler (ShedLock) removes members. */
    fun head(showtimeId: Long): QueueHead? =
        redis.opsForZSet().rangeWithScores(queueKey(showtimeId), 0, 0)?.firstOrNull()?.let {
            QueueHead(it.value!!.toLong(), it.score!!.toLong())
        }

    fun admit(showtimeId: Long, memberId: Long, accessToken: String, ttl: Duration): Boolean =
        redis.execute(
            admitScript,
            listOf(queueKey(showtimeId), polledKey(showtimeId), admittedKey(showtimeId, memberId)),
            memberId.toString(),
            accessToken,
            ttl.toSeconds().toString(),
        ) == 1L

    fun abandon(showtimeId: Long, memberId: Long): Boolean =
        redis.execute(abandonScript, listOf(queueKey(showtimeId), polledKey(showtimeId)), memberId.toString()) == 1L

    fun admittedToken(showtimeId: Long, memberId: Long): String? = redis.opsForValue().get(admittedKey(showtimeId, memberId))

    fun openShowtimes(): Set<Long> = redis.opsForSet().members(SHOWTIMES_KEY).orEmpty().map { it.toLong() }.toSet()

    fun waitingCount(showtimeId: Long): Long = redis.opsForZSet().zCard(queueKey(showtimeId)) ?: 0

    /** memberId → score (entered at, microseconds) for everyone still waiting. */
    fun waitingMembers(showtimeId: Long): Map<Long, Long> =
        redis.opsForZSet().rangeWithScores(queueKey(showtimeId), 0, -1).orEmpty()
            .associate { it.value!!.toLong() to it.score!!.toLong() }

    /** memberId → last poll time for every member in the poll hash. */
    fun polledAll(showtimeId: Long): Map<Long, Instant> =
        redis.opsForHash<String, String>().entries(polledKey(showtimeId))
            .map { (k, v) -> k.toLong() to Instant.ofEpochMilli(v.toLong()) }.toMap()

    companion object {
        const val SHOWTIMES_KEY = "queue:showtimes"
    }
}
