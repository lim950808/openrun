package dev.openrun.booking.hold

import org.springframework.stereotype.Component
import java.time.Instant

/** Pessimistic: `select ... for update` in seat order, waiters queue on the row lock and re-check after it. */
@Component
class ForUpdateClaim(
    private val rows: SeatHoldRows,
) : SeatClaimStrategy {
    override val name = LockStrategy.FOR_UPDATE

    override fun claim(showtimeId: Long, seatIds: List<Long>, reservationId: Long, memberId: Long, expiresAt: Instant): ClaimResult {
        val locked = rows.selectForUpdate(showtimeId, seatIds)
        if (locked.size < seatIds.size || locked.any { !it.free }) return ClaimResult.Taken
        rows.assign(showtimeId, seatIds, reservationId, memberId, expiresAt)
        return ClaimResult.Claimed(locked.associate { it.seatId to it.price })
    }
}
