package dev.openrun.booking.hold

import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Duration
import java.time.Instant

/**
 * Redis decides contention with one Lua call; the database row is then written without a guard
 * (ADR-0004 §1: the result is still recorded in `seat_hold`). If the surrounding transaction rolls back,
 * the Redis keys are released so the seats do not stay blocked until the TTL.
 */
@Component
class RedisLuaClaim(
    private val redisHolds: RedisSeatHolds,
    private val rows: SeatHoldRows,
) : SeatClaimStrategy {
    override val name = LockStrategy.REDIS_LUA

    override fun claim(showtimeId: Long, seatIds: List<Long>, reservationId: Long, memberId: Long, expiresAt: Instant): ClaimResult {
        val ttlMillis = Duration.between(Instant.now(), expiresAt).toMillis().coerceAtLeast(1)
        if (!redisHolds.claim(showtimeId, seatIds, reservationId, ttlMillis)) return ClaimResult.Taken
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCompletion(status: Int) {
                if (status != TransactionSynchronization.STATUS_COMMITTED) redisHolds.release(showtimeId, seatIds, reservationId)
            }
        })
        val found = rows.selectUnlocked(showtimeId, seatIds)
        if (found.size < seatIds.size) return ClaimResult.Taken
        rows.assign(showtimeId, seatIds, reservationId, memberId, expiresAt)
        return ClaimResult.Claimed(found.associate { it.seatId to it.price })
    }
}
