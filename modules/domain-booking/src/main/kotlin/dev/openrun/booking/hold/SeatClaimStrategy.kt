package dev.openrun.booking.hold

import java.time.Instant

/**
 * One way of claiming `seat_hold` rows for a reservation. Every strategy runs inside the caller's
 * transaction and leaves the same `seat_hold` rows on success, so the integrity query is strategy-agnostic
 * (ADR-0004 §1). [seatIds] arrive sorted ascending (deadlock avoidance).
 */
interface SeatClaimStrategy {
    val name: LockStrategy

    fun claim(showtimeId: Long, seatIds: List<Long>, reservationId: Long, memberId: Long, expiresAt: Instant): ClaimResult
}

sealed interface ClaimResult {
    /** [prices] maps seatId to the price snapshot. */
    data class Claimed(val prices: Map<Long, Int>) : ClaimResult

    data object Taken : ClaimResult
}
