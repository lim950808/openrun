package dev.openrun.booking.hold

import java.time.Duration
import java.time.Instant

/**
 * One way of claiming `seat_hold` rows for a reservation. Every strategy runs inside the caller's
 * transaction and leaves the same `seat_hold` rows on success, so the integrity query is strategy-agnostic
 * (ADR-0004 §1). [seatIds] arrive sorted ascending (deadlock avoidance).
 */
interface SeatClaimStrategy {
    val name: LockStrategy

    fun claim(showtimeId: Long, seatIds: List<Long>, reservationId: Long, memberId: Long, expiresAt: Instant): ClaimResult

    /** true when the strategy needs the reservation id before the transaction (allocated with `nextval`). */
    val allocatesIdUpfront: Boolean get() = false

    /**
     * Work before the database transaction opens, called only when [allocatesIdUpfront]. Returns null to continue,
     * or [ClaimResult.Taken] to stop before any database write.
     */
    fun beforeTransaction(showtimeId: Long, seatIds: List<Long>, reservationId: Long, ttl: Duration): ClaimResult? = null

    /** Undo of [beforeTransaction] when the transaction did not commit. Runs outside the transaction. */
    fun afterRollback(showtimeId: Long, seatIds: List<Long>, reservationId: Long) {}
}

sealed interface ClaimResult {
    /** [prices] maps seatId to the price snapshot. */
    data class Claimed(val prices: Map<Long, Int>) : ClaimResult

    data object Taken : ClaimResult
}
