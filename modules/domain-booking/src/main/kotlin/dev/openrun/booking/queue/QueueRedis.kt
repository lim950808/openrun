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

    // One tick's decisions in one step (ADR-0006 §2-3). Abandons: ZREM + HDEL. Admits: ZREM, and only if that
    // removed the member, HDEL + SET admitted key, so a poll never sees a member that is neither waiting nor
    // admitted. KEYS = [queue, polled, admitted key per admit]; ARGV = [ttl, nAbandon, abandon ids...,
    // (admit id, token)...]. Returns each ZREM result (0/1), abandons first, then admits.
    private val applyScript = DefaultRedisScript(
        """
        local ttl = ARGV[1]
        local nAbandon = tonumber(ARGV[2])
        local results = {}
        for i = 1, nAbandon do
          local member = ARGV[2 + i]
          local n = redis.call('ZREM', KEYS[1], member)
          redis.call('HDEL', KEYS[2], member)
          results[#results + 1] = n
        end
        local base = 2 + nAbandon
        for i = 1, #KEYS - 2 do
          local member = ARGV[base + 2 * i - 1]
          local n = redis.call('ZREM', KEYS[1], member)
          if n == 1 then
            redis.call('HDEL', KEYS[2], member)
            redis.call('SET', KEYS[2 + i], ARGV[base + 2 * i], 'EX', ttl)
          end
          results[#results + 1] = n
        end
        return results
        """.trimIndent(),
        List::class.java,
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

    /**
     * The first [limit] members by score (ZRANGE 0 limit-1 WITHSCORES), without removing them. Only the single
     * admission scheduler (ShedLock) removes members, so looking first and removing later is safe.
     */
    fun headCandidates(showtimeId: Long, limit: Long): List<QueueHead> =
        redis.opsForZSet().rangeWithScores(queueKey(showtimeId), 0, limit - 1).orEmpty()
            .map { QueueHead(it.value!!.toLong(), it.score!!.toLong()) }

    /** memberId → last poll time for [memberIds], one HMGET. Members that never polled are absent. */
    fun lastPolledAll(showtimeId: Long, memberIds: List<Long>): Map<Long, Instant> {
        if (memberIds.isEmpty()) return emptyMap()
        val values = redis.opsForHash<String, String>().multiGet(polledKey(showtimeId), memberIds.map { it.toString() })
        return memberIds.zip(values).mapNotNull { (id, v) -> v?.let { id to Instant.ofEpochMilli(it.toLong()) } }.toMap()
    }

    /**
     * Applies one tick's decisions with one Lua call. [admits] is (memberId, access token). Returns, abandons first
     * and then admits in the given order, whether each member was still in the queue (ZREM 1).
     */
    fun applyDecisions(showtimeId: Long, abandons: List<Long>, admits: List<Pair<Long, String>>, ttl: Duration): List<Boolean> {
        if (abandons.isEmpty() && admits.isEmpty()) return emptyList()
        val keys = listOf(queueKey(showtimeId), polledKey(showtimeId)) + admits.map { admittedKey(showtimeId, it.first) }
        val args = listOf(ttl.toSeconds().toString(), abandons.size.toString()) +
            abandons.map { it.toString() } +
            admits.flatMap { (memberId, token) -> listOf(memberId.toString(), token) }
        val result = redis.execute(applyScript, keys, *args.toTypedArray())
        return result.map { it == 1L }
    }

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
