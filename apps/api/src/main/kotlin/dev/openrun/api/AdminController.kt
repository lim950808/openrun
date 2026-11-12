package dev.openrun.api

import dev.openrun.booking.hold.IntegrityQuery
import dev.openrun.booking.hold.ShowtimeInventoryJdbc
import dev.openrun.booking.queue.QueueIntegrityQuery
import dev.openrun.catalog.SeatRepository
import dev.openrun.catalog.ShowRepository
import dev.openrun.catalog.Showtime
import dev.openrun.catalog.ShowtimeRepository
import dev.openrun.catalog.VenueRepository
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

data class CreateShowtimeRequest(val startsAt: Instant, val openAt: Instant)

data class CreateShowtimeResponse(val showtimeId: Long, val seatIds: Map<String, List<Long>>, val totalSeats: Int)

/** Operator endpoints for benchmarks and tests. No auth in 1차. */
@RestController
class AdminController(
    private val venues: VenueRepository,
    private val shows: ShowRepository,
    private val showtimes: ShowtimeRepository,
    private val seats: SeatRepository,
    private val inventory: ShowtimeInventoryJdbc,
    private val integrity: IntegrityQuery,
    private val queueIntegrity: QueueIntegrityQuery,
    private val registry: MeterRegistry,
) {
    /**
     * Two steps per ADR-0003 rule 2: catalog (JPA) creates the showtime and reads the seat map, then booking
     * (JDBC) provisions `seat_hold` rows from what was read. No JPA write shares the JDBC transaction.
     */
    @PostMapping("/api/admin/showtimes")
    @ResponseStatus(HttpStatus.CREATED)
    fun createShowtime(@RequestBody body: CreateShowtimeRequest): CreateShowtimeResponse {
        val venue = checkNotNull(venues.findFirstByNameOrderByIdAsc(DevSeeder.VENUE_NAME)) { "venue not seeded" }
        val show = checkNotNull(shows.findFirstByVenueIdOrderByIdAsc(venue.id!!)) { "show not seeded" }
        val showtime = showtimes.save(Showtime(show = show, startsAt = body.startsAt, openAt = body.openAt))
        val venueSeats = seats.findAllByVenueId(venue.id!!).sortedBy { it.id }

        inventory.provision(showtime.id!!, venueSeats.map { it.id!! to it.grade.price })

        return CreateShowtimeResponse(
            showtimeId = showtime.id!!,
            seatIds = venueSeats.groupBy({ it.grade.name }, { it.id!! }),
            totalSeats = venueSeats.size,
        )
    }

    /** Verification numbers with docs/metrics.md names. */
    @GetMapping("/api/admin/showtimes/{showtimeId}/integrity")
    fun integrity(@PathVariable showtimeId: Long): Map<String, Long> {
        val i = integrity.check(showtimeId)
        return linkedMapOf(
            "oversell_count" to i.oversellCount,
            "oversell_occurred_count" to i.oversellOccurredCount,
            "oversell_overlap_pairs" to i.oversellOverlapPairs,
            "held_seats" to i.heldSeats,
            "available_seats" to i.availableSeats,
            "holding_reservations" to i.holdingReservations,
            "expired_reservations" to i.expiredReservations,
            "expire_backlog_max_s" to i.expireBacklogMaxSeconds,
        )
    }

    /**
     * M2 verification numbers with docs/metrics.md names. `unauthorized_seat_calls` is the process-wide sum of
     * `openrun.api.errors{code=ACCESS_TOKEN_*}` (not per showtime: the counter has no showtime tag).
     */
    @GetMapping("/api/admin/showtimes/{showtimeId}/queue-integrity")
    fun queueIntegrity(@PathVariable showtimeId: Long): Map<String, Any?> {
        val q = queueIntegrity.check(showtimeId)
        val unauthorized = registry.find("openrun.api.errors").counters()
            .filter { it.id.getTag("code")?.startsWith("ACCESS_TOKEN_") == true }
            .sumOf { it.count() }
            .toLong()
        return linkedMapOf(
            "entered" to q.entered,
            "admitted" to q.admitted,
            "abandoned" to q.abandoned,
            "waiting" to q.waiting,
            "lost" to q.lost,
            "abandon_rate" to q.abandonRate,
            "fifo_violation_rate" to q.fifoViolationRate,
            "admission_delay_p50_s" to q.admissionDelayP50Seconds,
            "admission_delay_p99_s" to q.admissionDelayP99Seconds,
            "admission_rate_actual" to q.admissionRateActual,
            "unauthorized_seat_calls" to unauthorized,
        )
    }
}
