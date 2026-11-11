package dev.openrun.booking.hold

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

/** Post-run verification numbers for one showtime. Names follow docs/metrics.md (M1). */
data class Integrity(
    /** `oversell_count`: seats with two or more PAYING · CONFIRMED · unexpired HOLDING reservations. */
    val oversellCount: Long,
    /**
     * `oversell_occurred_count`: seats where two reservations' active intervals overlapped at any time, so a
     * double hold that was later cancelled or expired is still counted. Interval = [held_at, end) where end is
     * `updated_at` for CANCELLED, `least(updated_at, expires_at)` for EXPIRED, `expires_at` for HOLDING (seat status
     * is derived: an overdue HOLDING seat is already AVAILABLE and may be lazily re-claimed), and
     * `greatest(expires_at, now())` for PAYING · CONFIRMED.
     */
    val oversellOccurredCount: Long,
    /** `oversell_overlap_pairs`: reservation pairs on the same seat whose active intervals overlapped (same intervals). */
    val oversellOverlapPairs: Long,
    val heldSeats: Long,
    val availableSeats: Long,
    val holdingReservations: Long,
    val expiredReservations: Long,
    /** Oldest overdue HOLDING reservation the janitor has not expired yet, in seconds; 0 if none. */
    val expireBacklogMaxSeconds: Long,
)

@Repository
class IntegrityQuery(
    private val jdbc: JdbcTemplate,
) {
    fun check(showtimeId: Long): Integrity {
        val holding = ReservationStatus.HOLDING.name
        val paying = ReservationStatus.PAYING.name
        val confirmed = ReservationStatus.CONFIRMED.name

        val oversell = jdbc.queryForObject(
            """
            select count(*) from (
                select rs.seat_id from reservation_seat rs join reservation r on r.id = rs.reservation_id
                where r.showtime_id = ?
                  and (r.status in (?, ?) or (r.status = ? and r.expires_at > now()))
                group by rs.seat_id having count(*) >= 2
            ) x
            """.trimIndent(),
            Long::class.java,
            showtimeId, paying, confirmed, holding,
        ) ?: 0

        val occurred = jdbc.queryForMap(
            """
            with iv as (
                select rs.seat_id, r.id, coalesce(r.held_at, r.created_at) as s,
                       case r.status when ? then r.updated_at
                                     when ? then least(r.updated_at, r.expires_at)
                                     when ? then r.expires_at
                                     else greatest(r.expires_at, now()) end as e
                from reservation r join reservation_seat rs on rs.reservation_id = r.id
                where r.showtime_id = ?
            )
            select count(distinct a.seat_id) as seats, count(*) as pairs
            from iv a join iv b on a.seat_id = b.seat_id and a.id < b.id and a.s < b.e and b.s < a.e
            """.trimIndent(),
            ReservationStatus.CANCELLED.name, ReservationStatus.EXPIRED.name, holding, showtimeId,
        )

        val seats = jdbc.queryForMap(
            """
            select count(*) filter (where held) as held, count(*) filter (where not held) as available
            from (
                select sh.reservation_id is not null
                       and (r.status in (?, ?) or (r.status = ? and sh.expires_at > now())) as held
                from seat_hold sh left join reservation r on r.id = sh.reservation_id
                where sh.showtime_id = ?
            ) x
            """.trimIndent(),
            paying, confirmed, holding, showtimeId,
        )

        val reservations = jdbc.queryForMap(
            """
            select count(*) filter (where status = ?) as holding,
                   count(*) filter (where status = ?) as expired,
                   coalesce(extract(epoch from max(now() - expires_at) filter (where status = ? and expires_at <= now())), 0)::bigint
                       as backlog_s
            from reservation where showtime_id = ?
            """.trimIndent(),
            holding, ReservationStatus.EXPIRED.name, holding, showtimeId,
        )

        return Integrity(
            oversellCount = oversell,
            oversellOccurredCount = (occurred["seats"] as Number).toLong(),
            oversellOverlapPairs = (occurred["pairs"] as Number).toLong(),
            heldSeats = (seats["held"] as Number).toLong(),
            availableSeats = (seats["available"] as Number).toLong(),
            holdingReservations = (reservations["holding"] as Number).toLong(),
            expiredReservations = (reservations["expired"] as Number).toLong(),
            expireBacklogMaxSeconds = (reservations["backlog_s"] as Number).toLong(),
        )
    }
}
