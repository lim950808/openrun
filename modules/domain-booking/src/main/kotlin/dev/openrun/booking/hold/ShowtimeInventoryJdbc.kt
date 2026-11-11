package dev.openrun.booking.hold

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

/** Pre-populates one free `seat_hold` row per showtime seat (ADR-0004 §1). Idempotent. */
@Repository
class ShowtimeInventoryJdbc(
    private val jdbc: JdbcTemplate,
) {
    /** [seats] = (seatId, price snapshot source). */
    @Transactional
    fun provision(showtimeId: Long, seats: List<Pair<Long, Int>>) {
        jdbc.batchUpdate(
            "insert into seat_hold (showtime_id, seat_id, price) values (?, ?, ?) on conflict do nothing",
            seats.map { (seatId, price) -> arrayOf<Any>(showtimeId, seatId, price) },
        )
    }
}
