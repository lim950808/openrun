package dev.openrun.api

import dev.openrun.booking.hold.HoldCommand
import dev.openrun.booking.hold.HoldExpiryJanitor
import dev.openrun.booking.hold.HoldOutcome
import dev.openrun.booking.hold.HoldService
import dev.openrun.booking.hold.IntegrityQuery
import dev.openrun.booking.hold.LockStrategy
import dev.openrun.booking.hold.SeatStatus
import dev.openrun.booking.hold.SeatStatusQuery
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate

/** M1-2 with a 1-second TTL (separate context: only the TTL differs from the shared test properties). */
@SpringBootTest(properties = ["openrun.hold.ttl=PT1S"])
@Import(TestcontainersConfig::class)
class HoldExpiryTest @Autowired constructor(
    private val holds: HoldService,
    private val janitor: HoldExpiryJanitor,
    private val admin: AdminController,
    private val seatStatus: SeatStatusQuery,
    private val jdbc: JdbcTemplate,
    private val integrity: IntegrityQuery,
) {
    private fun holdOne(showtimeId: Long, memberId: Long, seatId: Long, strategy: LockStrategy): HoldOutcome =
        holds.hold(HoldCommand(showtimeId, memberId, listOf(seatId), strategy))

    private fun waitPastTtl() = Thread.sleep(1_500)

    private fun reservationStatus(id: Long): String =
        jdbc.queryForObject("select status from reservation where id = ?", String::class.java, id)!!

    @Test
    fun `M1_2 만료 후 재선점`() {
        val showtime = admin.newShowtime()
        val seat = showtime.seatIds.getValue("VIP").first()
        val first = holdOne(showtime.showtimeId, 1, seat, LockStrategy.REDIS_LUA) as HoldOutcome.Success
        val reservationId = first.result.reservationId
        waitPastTtl()

        assertThat(janitor.sweep()).isGreaterThanOrEqualTo(1)

        assertThat(reservationStatus(reservationId)).isEqualTo("EXPIRED")
        assertThat(seatStatus.seats(showtime.showtimeId).single { it.seatId == seat }.status).isEqualTo(SeatStatus.AVAILABLE)
        val heldBy = jdbc.queryForList(
            "select reservation_id from seat_hold where showtime_id = ? and seat_id = ?",
            Long::class.javaObjectType,
            showtime.showtimeId,
            seat,
        )
        assertThat(heldBy).containsExactly(null)
        val events = jdbc.queryForList(
            "select event_type from outbox where aggregate_type = 'reservation' and aggregate_id = ? order by id",
            String::class.java,
            reservationId.toString(),
        )
        assertThat(events).containsExactly("ReservationHeld", "ReservationExpired")
        assertThat(holdOne(showtime.showtimeId, 2, seat, LockStrategy.REDIS_LUA)).isInstanceOf(HoldOutcome.Success::class.java)
    }

    @Test
    fun `M1_2 janitor 전에도 만료된 좌석은 lazy하게 재선점된다`() {
        val showtime = admin.newShowtime()
        val seat = showtime.seatIds.getValue("R").first()
        val first = holdOne(showtime.showtimeId, 1, seat, LockStrategy.CONDITIONAL_UPDATE) as HoldOutcome.Success
        waitPastTtl()

        val second = holdOne(showtime.showtimeId, 2, seat, LockStrategy.CONDITIONAL_UPDATE)

        assertThat(second).isInstanceOf(HoldOutcome.Success::class.java)
        assertThat(reservationStatus(first.result.reservationId)).isEqualTo("HOLDING")
        // The janitor later expires the first reservation without touching the seat now owned by the second.
        janitor.sweep()
        assertThat(reservationStatus(first.result.reservationId)).isEqualTo("EXPIRED")
        val owner = jdbc.queryForObject(
            "select reservation_id from seat_hold where showtime_id = ? and seat_id = ?",
            Long::class.java,
            showtime.showtimeId,
            seat,
        )
        assertThat(owner).isEqualTo((second as HoldOutcome.Success).result.reservationId)
        // Sequential holders (first expired, then second) are not an overlap.
        assertThat(integrity.check(showtime.showtimeId).oversellOccurredCount).isZero()
    }
}
