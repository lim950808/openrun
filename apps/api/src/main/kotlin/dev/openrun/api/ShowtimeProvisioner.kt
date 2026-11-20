package dev.openrun.api

import dev.openrun.booking.hold.ShowtimeInventoryJdbc
import dev.openrun.catalog.SeatRepository
import dev.openrun.catalog.ShowRepository
import dev.openrun.catalog.Showtime
import dev.openrun.catalog.ShowtimeRepository
import dev.openrun.catalog.VenueRepository
import org.springframework.stereotype.Component
import java.time.Instant

data class CreateShowtimeResponse(val showtimeId: Long, val seatIds: Map<String, List<Long>>, val totalSeats: Int)

/**
 * Creates a showtime with its full seat inventory, for the operator API and the demo scheduler (ADR-0009 §4).
 *
 * Two steps per ADR-0003 rule 2: catalog (JPA) creates the showtime and reads the seat map, then booking (JDBC)
 * provisions `seat_hold` rows from what was read. No JPA write shares the JDBC transaction.
 */
@Component
class ShowtimeProvisioner(
    private val venues: VenueRepository,
    private val shows: ShowRepository,
    private val showtimes: ShowtimeRepository,
    private val seats: SeatRepository,
    private val inventory: ShowtimeInventoryJdbc,
) {
    fun create(startsAt: Instant, openAt: Instant): CreateShowtimeResponse {
        val venue = checkNotNull(venues.findFirstByNameOrderByIdAsc(DevSeeder.VENUE_NAME)) { "venue not seeded" }
        val show = checkNotNull(shows.findFirstByVenueIdOrderByIdAsc(venue.id!!)) { "show not seeded" }
        val showtime = showtimes.save(Showtime(show = show, startsAt = startsAt, openAt = openAt))
        val venueSeats = seats.findAllByVenueId(venue.id!!).sortedBy { it.id }

        inventory.provision(showtime.id!!, venueSeats.map { it.id!! to it.grade.price })

        return CreateShowtimeResponse(
            showtimeId = showtime.id!!,
            seatIds = venueSeats.groupBy({ it.grade.name }, { it.id!! }),
            totalSeats = venueSeats.size,
        )
    }
}
