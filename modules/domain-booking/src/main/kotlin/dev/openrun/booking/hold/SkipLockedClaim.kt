package dev.openrun.booking.hold

import org.springframework.stereotype.Component
import java.time.Instant

/** Fail-fast pessimistic: `for update skip locked`; a seat locked by someone else is treated as taken. */
@Component
class SkipLockedClaim(
    private val rows: SeatHoldRows,
) : SeatClaimStrategy {
    override val name = LockStrategy.SKIP_LOCKED

    override fun claim(showtimeId: Long, seatIds: List<Long>, reservationId: Long, memberId: Long, expiresAt: Instant): ClaimResult {
        val locked = rows.selectForUpdateSkipLocked(showtimeId, seatIds)
        if (locked.size < seatIds.size || locked.any { !it.free }) return ClaimResult.Taken
        rows.assign(showtimeId, seatIds, reservationId, memberId, expiresAt)
        return ClaimResult.Claimed(locked.associate { it.seatId to it.price })
    }
}
