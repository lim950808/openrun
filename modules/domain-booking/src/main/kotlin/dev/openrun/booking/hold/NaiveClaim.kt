package dev.openrun.booking.hold

import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Anti-pattern kept on purpose (M1-3): read without a lock, then write without a guard. Two requests that read
 * "free" before either writes both succeed, and both reservations keep their `reservation_seat` rows: oversell.
 */
@Component
class NaiveClaim(
    private val rows: SeatHoldRows,
    private val props: HoldProperties,
) : SeatClaimStrategy {
    override val name = LockStrategy.NAIVE

    override fun claim(showtimeId: Long, seatIds: List<Long>, reservationId: Long, memberId: Long, expiresAt: Instant): ClaimResult {
        val found = rows.selectUnlocked(showtimeId, seatIds)
        if (found.size < seatIds.size || found.any { !it.free }) return ClaimResult.Taken
        if (props.naiveDelayMs > 0) Thread.sleep(props.naiveDelayMs)
        rows.assign(showtimeId, seatIds, reservationId, memberId, expiresAt)
        return ClaimResult.Claimed(found.associate { it.seatId to it.price })
    }
}
