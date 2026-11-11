package dev.openrun.booking.hold

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Component

/** Atomic seat hold in Redis (lock strategy `REDIS_LUA`): SET NX PX in one script call. */
@Component
class RedisHoldScript(
    private val redis: StringRedisTemplate,
) {
    private val script = DefaultRedisScript(
        "if redis.call('SET', KEYS[1], ARGV[1], 'NX', 'PX', ARGV[2]) then return 1 else return 0 end",
        Long::class.java,
    )

    fun tryHold(seatKey: String, memberId: String, ttlMillis: Long): Boolean =
        redis.execute(script, listOf(seatKey), memberId, ttlMillis.toString()) == 1L
}
