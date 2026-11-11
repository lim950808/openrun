package dev.openrun.api

import dev.openrun.catalog.Seat
import dev.openrun.catalog.SeatGrade
import dev.openrun.catalog.SeatRepository
import dev.openrun.catalog.Show
import dev.openrun.catalog.ShowRepository
import dev.openrun.catalog.Venue
import dev.openrun.catalog.VenueRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.CommandLineRunner
import org.springframework.stereotype.Component

/**
 * Seeds the single venue and show of docs/requirements.md §1 (1,500 seats = VIP 300 · R 600 · S 600).
 * Idempotent: does nothing once the venue exists.
 */
@Component
class DevSeeder(
    private val venues: VenueRepository,
    private val shows: ShowRepository,
    private val seats: SeatRepository,
) : CommandLineRunner {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(vararg args: String) {
        if (venues.findFirstByNameOrderByIdAsc(VENUE_NAME) != null) return

        val venue = venues.save(Venue(name = VENUE_NAME))
        shows.save(Show(title = SHOW_TITLE, venue = venue))
        val layout = listOf(SeatGrade.VIP to ('V' to 10), SeatGrade.R to ('R' to 20), SeatGrade.S to ('S' to 20))
        val all = layout.flatMap { (grade, rows) ->
            val (prefix, rowCount) = rows
            (1..rowCount).flatMap { row ->
                val label = prefix + row.toString().padStart(2, '0')
                (1..SEATS_PER_ROW).map { no -> Seat(venue = venue, grade = grade, rowLabel = label, seatNo = no) }
            }
        }
        all.chunked(BATCH).forEach { seats.saveAll(it) }
        log.info("seeded venue={} seats={}", venue.id, all.size)
    }

    companion object {
        const val VENUE_NAME = "오픈런 아레나"
        const val SHOW_TITLE = "연말 콘서트"
        private const val SEATS_PER_ROW = 30
        private const val BATCH = 300
    }
}
