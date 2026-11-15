package dev.openrun.api

import dev.openrun.booking.queue.QueueProperties
import dev.openrun.catalog.SeatRepository
import dev.openrun.catalog.Showtime
import dev.openrun.catalog.ShowtimeRepository
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

data class ShowtimeBody(
    val showtimeId: Long,
    val showTitle: String,
    val venueName: String,
    val startsAt: Instant,
    val openAt: Instant,
    val entryOpensAt: Instant,
    val totalSeats: Long,
)

/** Public showtime lookup for the web home and queue pages (ADR-0008 §1). Catalog (JPA) reads only. */
@RestController
class ShowtimeController(
    private val showtimes: ShowtimeRepository,
    private val seats: SeatRepository,
    private val props: QueueProperties,
    private val clock: Clock,
    private val errors: ApiErrors,
) {
    /** Seat count per venue. Venue seat maps are master data and do not change while the app runs. */
    private val seatCounts = ConcurrentHashMap<Long, Long>()

    @GetMapping("/api/showtimes")
    @Transactional(readOnly = true)
    fun list(): List<ShowtimeBody> = showtimes.findAllByStartsAtAfterOrderByOpenAtAscIdAsc(clock.instant()).map(::body)

    @GetMapping("/api/showtimes/{showtimeId}")
    @Transactional(readOnly = true)
    fun detail(@PathVariable showtimeId: Long): ResponseEntity<Any> {
        val showtime = showtimes.findWithShowById(showtimeId) ?: return errors.respond(HttpStatus.NOT_FOUND, "SHOWTIME_NOT_FOUND")
        return ResponseEntity.ok(body(showtime))
    }

    private fun body(s: Showtime): ShowtimeBody {
        val venue = s.show.venue
        return ShowtimeBody(
            showtimeId = s.id!!,
            showTitle = s.show.title,
            venueName = venue.name,
            startsAt = s.startsAt,
            openAt = s.openAt,
            entryOpensAt = s.openAt.minus(props.entryLead),
            totalSeats = seatCounts.computeIfAbsent(venue.id!!) { seats.countByVenueId(it) },
        )
    }
}
