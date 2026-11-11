package dev.openrun.booking.hold

import dev.openrun.outbox.OutboxJdbcRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

sealed interface ReleaseOutcome {
    data object Released : ReleaseOutcome

    /** Not HOLDING, not the owner, or no such reservation (409, counted as `illegal_transition_count`). */
    data object IllegalTransition : ReleaseOutcome
}

/** HOLDING → CANCELLED by the owner (ADR-0004 §2). */
@Service
class ReleaseService(
    private val jdbc: JdbcTemplate,
    private val outbox: OutboxJdbcRepository,
    private val redisHolds: RedisSeatHolds,
) {
    @Transactional
    fun release(reservationId: Long, memberId: Long): ReleaseOutcome {
        val showtimeId = jdbc.query(
            """
            update reservation set status = ?, updated_at = now()
            where id = ? and member_id = ? and status = ?
            returning showtime_id
            """.trimIndent(),
            { rs, _ -> rs.getLong("showtime_id") },
            ReservationStatus.CANCELLED.name,
            reservationId,
            memberId,
            ReservationStatus.HOLDING.name,
        ).singleOrNull() ?: return ReleaseOutcome.IllegalTransition

        jdbc.update(
            "update seat_hold set reservation_id = null, member_id = null, expires_at = null where reservation_id = ?",
            reservationId,
        )
        val seatIds = jdbc.query(
            "select seat_id from reservation_seat where reservation_id = ? order by seat_id",
            { rs, _ -> rs.getLong("seat_id") },
            reservationId,
        )
        redisHolds.release(showtimeId, seatIds, reservationId)
        outbox.append(ReservationEvents.cancelled(reservationId, showtimeId, memberId, seatIds))
        return ReleaseOutcome.Released
    }
}
