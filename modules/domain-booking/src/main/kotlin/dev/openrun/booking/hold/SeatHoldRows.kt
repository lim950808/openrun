package dev.openrun.booking.hold

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Row read by the select-then-update strategies. [free] is computed by the database with the same predicate the
 * conditional update uses (`reservation_id is null or expires_at < now()`), so all strategies share one clock.
 */
data class SeatHoldRow(val seatId: Long, val price: Int, val free: Boolean)

/** SQL shared by the select-then-update strategies. Each statement is spelled out in full on purpose. */
@Component
class SeatHoldRows(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    fun selectUnlocked(showtimeId: Long, seatIds: List<Long>): List<SeatHoldRow> =
        jdbc.query(
            """
            select seat_id, price, (reservation_id is null or expires_at < now()) as free from seat_hold
            where showtime_id = :showtimeId and seat_id in (:seatIds)
            order by seat_id
            """.trimIndent(),
            mapOf("showtimeId" to showtimeId, "seatIds" to seatIds),
        ) { rs, _ -> row(rs) }

    fun selectForUpdate(showtimeId: Long, seatIds: List<Long>): List<SeatHoldRow> =
        jdbc.query(
            """
            select seat_id, price, (reservation_id is null or expires_at < now()) as free from seat_hold
            where showtime_id = :showtimeId and seat_id in (:seatIds)
            order by seat_id
            for update
            """.trimIndent(),
            mapOf("showtimeId" to showtimeId, "seatIds" to seatIds),
        ) { rs, _ -> row(rs) }

    fun selectForUpdateSkipLocked(showtimeId: Long, seatIds: List<Long>): List<SeatHoldRow> =
        jdbc.query(
            """
            select seat_id, price, (reservation_id is null or expires_at < now()) as free from seat_hold
            where showtime_id = :showtimeId and seat_id in (:seatIds)
            order by seat_id
            for update skip locked
            """.trimIndent(),
            mapOf("showtimeId" to showtimeId, "seatIds" to seatIds),
        ) { rs, _ -> row(rs) }

    /** Unguarded write: correctness depends entirely on what the strategy did before calling it. */
    fun assign(showtimeId: Long, seatIds: List<Long>, reservationId: Long, memberId: Long, expiresAt: Instant): Int =
        jdbc.update(
            """
            update seat_hold set reservation_id = :reservationId, member_id = :memberId, expires_at = :expiresAt
            where showtime_id = :showtimeId and seat_id in (:seatIds)
            """.trimIndent(),
            mapOf(
                "reservationId" to reservationId,
                "memberId" to memberId,
                "expiresAt" to expiresAt.toOffset(),
                "showtimeId" to showtimeId,
                "seatIds" to seatIds,
            ),
        )

    private fun row(rs: java.sql.ResultSet) = SeatHoldRow(
        seatId = rs.getLong("seat_id"),
        price = rs.getInt("price"),
        free = rs.getBoolean("free"),
    )
}

internal fun Instant.toOffset(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)
