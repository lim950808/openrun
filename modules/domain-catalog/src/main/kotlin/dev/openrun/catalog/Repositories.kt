package dev.openrun.catalog

import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

interface VenueRepository : JpaRepository<Venue, Long> {
    fun findFirstByNameOrderByIdAsc(name: String): Venue?
}

interface ShowRepository : JpaRepository<Show, Long> {
    fun findFirstByVenueIdOrderByIdAsc(venueId: Long): Show?
}

interface ShowtimeRepository : JpaRepository<Showtime, Long> {
    /** Public showtime list (ADR-0008 §1, ADR-0009 §4): the 3 earliest not yet started, show and venue fetched with it. */
    @EntityGraph(attributePaths = ["show", "show.venue"])
    fun findFirst3ByStartsAtAfterOrderByOpenAtAscIdAsc(now: Instant): List<Showtime>

    /** Demo scheduler idempotency (ADR-0009 §4): is there already a showtime opening at this instant? */
    fun existsByOpenAt(openAt: Instant): Boolean

    @EntityGraph(attributePaths = ["show", "show.venue"])
    fun findWithShowById(id: Long): Showtime?
}

interface SeatRepository : JpaRepository<Seat, Long> {
    fun findAllByVenueId(venueId: Long): List<Seat>

    fun countByVenueId(venueId: Long): Long
}

interface MemberRepository : JpaRepository<Member, Long>
