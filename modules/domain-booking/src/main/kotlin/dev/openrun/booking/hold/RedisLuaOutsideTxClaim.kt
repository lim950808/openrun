package dev.openrun.booking.hold

import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

/**
 * `REDIS_LUA` with the Redis round-trip moved before the database transaction (ADR-0005 §7, experiment variant).
 * The Lua call decides contention with the pre-allocated reservation id as owner; inside the transaction only the
 * `seat_hold` rows are checked to exist and written. The key outlives the row by one second (`expires_at` is set a
 * few ms later by the INSERT); expiry and payment hooks delete it by owner as for `REDIS_LUA`.
 */
@Component
class RedisLuaOutsideTxClaim(
    private val redisHolds: RedisSeatHolds,
    private val rows: SeatHoldRows,
) : SeatClaimStrategy {
    override val name = LockStrategy.REDIS_LUA_OUTSIDE_TX

    override val allocatesIdUpfront = true

    override fun beforeTransaction(showtimeId: Long, seatIds: List<Long>, reservationId: Long, ttl: Duration): ClaimResult? =
        if (redisHolds.claim(showtimeId, seatIds, reservationId, ttl.toMillis() + KEY_TTL_MARGIN_MS, name)) null else ClaimResult.Taken

    override fun claim(showtimeId: Long, seatIds: List<Long>, reservationId: Long, memberId: Long, expiresAt: Instant): ClaimResult {
        val found = rows.selectUnlocked(showtimeId, seatIds)
        if (found.size < seatIds.size) return ClaimResult.Taken
        rows.assign(showtimeId, seatIds, reservationId, memberId, expiresAt)
        return ClaimResult.Claimed(found.associate { it.seatId to it.price })
    }

    override fun afterRollback(showtimeId: Long, seatIds: List<Long>, reservationId: Long) {
        redisHolds.release(showtimeId, seatIds, reservationId)
    }

    private companion object {
        const val KEY_TTL_MARGIN_MS = 1_000L
    }
}
