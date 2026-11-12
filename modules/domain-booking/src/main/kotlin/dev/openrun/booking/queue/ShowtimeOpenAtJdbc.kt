package dev.openrun.booking.queue

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.OffsetDateTime

/** Read-only lookup of `showtime.open_at` for the queue (catalog table, plain SQL, no JPA in this module). */
@Repository
class ShowtimeOpenAtJdbc(
    private val jdbc: JdbcTemplate,
) {
    /** null when the showtime does not exist. */
    fun openAt(showtimeId: Long): Instant? =
        jdbc.query(
            "select open_at from showtime where id = ?",
            { rs, _ -> rs.getObject("open_at", OffsetDateTime::class.java).toInstant() },
            showtimeId,
        ).singleOrNull()
}
