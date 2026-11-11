package dev.openrun.booking.hold

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

data class SeatView(
    val seatId: Long,
    val grade: String,
    val rowLabel: String,
    val seatNo: Int,
    val price: Int,
    val status: SeatStatus,
)

/** Derived seat status for one showtime, ADR-0004 §1. */
@Repository
class SeatStatusQuery(
    private val jdbc: JdbcTemplate,
) {
    fun seats(showtimeId: Long): List<SeatView> =
        jdbc.query(
            """
            select sh.seat_id, s.grade, s.row_label, s.seat_no, sh.price,
              case when sh.reservation_id is null then 'AVAILABLE'
                   when r.status = ? then 'SOLD'
                   when r.status = ? then 'HELD'
                   when r.status = ? and sh.expires_at > now() then 'HELD'
                   else 'AVAILABLE' end as status
            from seat_hold sh join seat s on s.id = sh.seat_id
            left join reservation r on r.id = sh.reservation_id
            where sh.showtime_id = ?
            order by s.grade, s.row_label, s.seat_no
            """.trimIndent(),
            { rs, _ ->
                SeatView(
                    seatId = rs.getLong("seat_id"),
                    grade = rs.getString("grade"),
                    rowLabel = rs.getString("row_label"),
                    seatNo = rs.getInt("seat_no"),
                    price = rs.getInt("price"),
                    status = SeatStatus.valueOf(rs.getString("status")),
                )
            },
            ReservationStatus.CONFIRMED.name,
            ReservationStatus.PAYING.name,
            ReservationStatus.HOLDING.name,
            showtimeId,
        )
}
