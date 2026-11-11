package dev.openrun.catalog

import org.springframework.data.jpa.repository.JpaRepository

interface VenueRepository : JpaRepository<Venue, Long> {
    fun findFirstByNameOrderByIdAsc(name: String): Venue?
}

interface ShowRepository : JpaRepository<Show, Long> {
    fun findFirstByVenueIdOrderByIdAsc(venueId: Long): Show?
}

interface ShowtimeRepository : JpaRepository<Showtime, Long>

interface SeatRepository : JpaRepository<Seat, Long> {
    fun findAllByVenueId(venueId: Long): List<Seat>
}

interface MemberRepository : JpaRepository<Member, Long>
