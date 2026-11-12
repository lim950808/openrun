package dev.openrun.payment

import java.time.Instant

/**
 * Seat-side cleanup that lives outside the database (the `REDIS_LUA` strategy's Redis keys). domain-payment does
 * not depend on domain-booking, so apps/api implements this by delegating to booking's Redis helper. Every method
 * runs inside the transition's transaction and only touches keys still owned by [reservationId].
 */
interface SeatHoldHook {
    /** HOLDING → PAYING: the seat stays HELD past `expires_at` (ADR-0004 §1), so drop the key's TTL. */
    fun pin(showtimeId: Long, seatIds: List<Long>, reservationId: Long)

    /** PAYING → HOLDING: the hold's own expiry applies again. */
    fun unpin(showtimeId: Long, seatIds: List<Long>, reservationId: Long, expiresAt: Instant)

    /** → EXPIRED · CANCELLED: the seats are AVAILABLE. */
    fun release(showtimeId: Long, seatIds: List<Long>, reservationId: Long)

    object None : SeatHoldHook {
        override fun pin(showtimeId: Long, seatIds: List<Long>, reservationId: Long) = Unit

        override fun unpin(showtimeId: Long, seatIds: List<Long>, reservationId: Long, expiresAt: Instant) = Unit

        override fun release(showtimeId: Long, seatIds: List<Long>, reservationId: Long) = Unit
    }
}
