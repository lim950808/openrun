package dev.openrun.booking.hold

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Guarded single-row updates: the free-check lives in the WHERE clause, so Postgres re-evaluates it after waiting
 * on a concurrent writer. One statement per seat, executed in ascending seat_id order, gives a deterministic lock
 * order: a multi-row `seat_id in (...)` update locks rows in plan order, which can deadlock two overlapping
 * 2-seat requests. Any seat that matches 0 rows returns Taken and the caller's rollback undoes the earlier rows.
 */
@Component
class ConditionalUpdateClaim(
    private val jdbc: JdbcTemplate,
) : SeatClaimStrategy {
    override val name = LockStrategy.CONDITIONAL_UPDATE

    override fun claim(showtimeId: Long, seatIds: List<Long>, reservationId: Long, memberId: Long, expiresAt: Instant): ClaimResult {
        val prices = LinkedHashMap<Long, Int>()
        for (seatId in seatIds.sorted()) {
            val price = jdbc.query(
                """
                update seat_hold set reservation_id = ?, member_id = ?, expires_at = ?
                where showtime_id = ? and seat_id = ?
                  and (reservation_id is null or expires_at < now())
                returning price
                """.trimIndent(),
                { rs, _ -> rs.getInt("price") },
                reservationId,
                memberId,
                expiresAt.toOffset(),
                showtimeId,
                seatId,
            ).singleOrNull() ?: return ClaimResult.Taken
            prices[seatId] = price
        }
        return ClaimResult.Claimed(prices)
    }
}
