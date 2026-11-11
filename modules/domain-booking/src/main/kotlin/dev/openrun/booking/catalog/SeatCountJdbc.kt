package dev.openrun.booking.catalog

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

/** Read-only JDBC access to the catalog `seat` table (allowed by ADR-0003; no JPA in this module). */
@Repository
class SeatCountJdbc(
    private val jdbc: JdbcTemplate,
) {
    fun countByVenue(venueId: Long): Long =
        jdbc.queryForObject("select count(*) from seat where venue_id = ?", Long::class.java, venueId) ?: 0L
}
