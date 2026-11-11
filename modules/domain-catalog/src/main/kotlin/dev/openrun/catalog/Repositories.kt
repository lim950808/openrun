package dev.openrun.catalog

import org.springframework.data.jpa.repository.JpaRepository

interface VenueRepository : JpaRepository<Venue, Long>

interface ShowRepository : JpaRepository<Show, Long>

interface ShowtimeRepository : JpaRepository<Showtime, Long>

interface SeatRepository : JpaRepository<Seat, Long> {
    fun findAllByVenueId(venueId: Long): List<Seat>
}

interface MemberRepository : JpaRepository<Member, Long>
