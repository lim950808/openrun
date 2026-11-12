package dev.openrun.booking.hold

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Component

/**
 * Redis side of the `REDIS_LUA` strategy. Key `hold:{showtimeId}:{seatId}` (the braces are a cluster hash tag so
 * one showtime's keys share a slot); value is the reservation id so cleanup only deletes keys it still owns.
 */
@Component
class RedisSeatHolds(
    private val redis: StringRedisTemplate,
    private val registry: MeterRegistry,
) {
    private val claimScript = DefaultRedisScript(
        """
        for i, k in ipairs(KEYS) do
          if redis.call('EXISTS', k) == 1 then return 0 end
        end
        for i, k in ipairs(KEYS) do
          redis.call('SET', k, ARGV[1], 'PX', ARGV[2])
        end
        return 1
        """.trimIndent(),
        Long::class.java,
    )

    private val releaseScript = DefaultRedisScript(
        """
        local n = 0
        for i, k in ipairs(KEYS) do
          if redis.call('GET', k) == ARGV[1] then n = n + redis.call('DEL', k) end
        end
        return n
        """.trimIndent(),
        Long::class.java,
    )

    /** ARGV[2] empty → PERSIST (no TTL), else PEXPIREAT ARGV[2]. Only keys still owned by ARGV[1]. */
    private val pinScript = DefaultRedisScript(
        """
        local n = 0
        for i, k in ipairs(KEYS) do
          if redis.call('GET', k) == ARGV[1] then
            if ARGV[2] == '' then n = n + redis.call('PERSIST', k) else n = n + redis.call('PEXPIREAT', k, ARGV[2]) end
          end
        end
        return n
        """.trimIndent(),
        Long::class.java,
    )

    fun keys(showtimeId: Long, seatIds: List<Long>): List<String> = seatIds.map { "hold:{$showtimeId}:$it" }

    /** All-or-nothing SET for every key; false when any key already exists. Timed as phase `redis_script`. */
    fun claim(showtimeId: Long, seatIds: List<Long>, reservationId: Long, ttlMillis: Long): Boolean {
        val started = System.nanoTime()
        try {
            return redis.execute(claimScript, keys(showtimeId, seatIds), reservationId.toString(), ttlMillis.toString()) == 1L
        } finally {
            HoldService.recordPhase(registry, LockStrategy.REDIS_LUA, HoldService.PHASE_REDIS_SCRIPT, started)
        }
    }

    /**
     * PAYING keeps the seat past `expires_at` (ADR-0004 §1): [expireAtMillis] null removes the TTL, a value restores
     * it when the reservation goes back to HOLDING. Harmless when the strategy never wrote the keys.
     */
    fun pin(showtimeId: Long, seatIds: List<Long>, reservationId: Long, expireAtMillis: Long?): Long =
        if (seatIds.isEmpty()) 0 else redis.execute(pinScript, keys(showtimeId, seatIds), reservationId.toString(), expireAtMillis?.toString() ?: "") ?: 0

    /** Deletes only keys still owned by [reservationId]; harmless when the strategy never wrote them. */
    fun release(showtimeId: Long, seatIds: List<Long>, reservationId: Long): Long =
        if (seatIds.isEmpty()) 0 else redis.execute(releaseScript, keys(showtimeId, seatIds), reservationId.toString()) ?: 0
}
