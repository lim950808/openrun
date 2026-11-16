package dev.openrun.api

import dev.openrun.booking.hold.HoldCommand
import dev.openrun.booking.hold.HoldError
import dev.openrun.booking.hold.HoldExpiryJanitor
import dev.openrun.booking.hold.HoldOutcome
import dev.openrun.booking.hold.HoldProperties
import dev.openrun.booking.hold.HoldService
import dev.openrun.booking.hold.IntegrityQuery
import dev.openrun.booking.hold.LockStrategy
import dev.openrun.booking.hold.SeatStatus
import dev.openrun.booking.hold.SeatStatusQuery
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import java.util.concurrent.TimeUnit

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
    private val redis: StringRedisTemplate,
    private val props: HoldProperties,
) {
    private fun holdOne(showtimeId: Long, memberId: Long, seatId: Long, strategy: LockStrategy): HoldOutcome =
        holds.hold(HoldCommand(showtimeId, memberId, listOf(seatId), strategy))

    private fun waitPastTtl() = Thread.sleep(1_500)

    private fun reservationStatus(id: Long): String =
        jdbc.queryForObject("select status from reservation where id = ?", String::class.java, id)!!

    private fun redisKey(showtimeId: Long, seatId: Long) = "hold:{$showtimeId}:$seatId"

    private fun reservationsOn(showtimeId: Long, seatId: Long): List<Long?> =
        jdbc.queryForList(
            """
            select r.id from reservation r join reservation_seat rs on rs.reservation_id = r.id
            where r.showtime_id = ? and rs.seat_id = ?
            """.trimIndent(),
            Long::class.java,
            showtimeId,
            seatId,
        )

    @ParameterizedTest(name = "M1_2 {0} 만료 후 재선점")
    @EnumSource(LockStrategy::class, names = ["REDIS_LUA", "REDIS_LUA_OUTSIDE_TX"])
    fun `M1_2 만료 후 재선점`(strategy: LockStrategy) {
        val showtime = admin.newShowtime()
        val seat = showtime.seatIds.getValue("VIP").first()
        val first = holdOne(showtime.showtimeId, 1, seat, strategy) as HoldOutcome.Success
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
        assertThat(redis.hasKey(redisKey(showtime.showtimeId, seat))).isFalse()
        assertThat(holdOne(showtime.showtimeId, 2, seat, strategy)).isInstanceOf(HoldOutcome.Success::class.java)
    }

    @Test
    fun `M1_3 REDIS_LUA_OUTSIDE_TX SEAT_TAKEN이면 Redis 키가 남지 않는다`() {
        val showtime = admin.newShowtime()
        val seat = showtime.seatIds.getValue("VIP").first()
        val first = holdOne(showtime.showtimeId, 1, seat, LockStrategy.REDIS_LUA_OUTSIDE_TX) as HoldOutcome.Success

        val second = holdOne(showtime.showtimeId, 2, seat, LockStrategy.REDIS_LUA_OUTSIDE_TX)

        assertThat(second).isEqualTo(HoldOutcome.Failure(HoldError.SeatTaken))
        // The loser stopped before the database and left the winner's key as it was.
        assertThat(redis.opsForValue().get(redisKey(showtime.showtimeId, seat))).isEqualTo(first.result.reservationId.toString())
        val loserRows = jdbc.queryForObject(
            "select count(*) from reservation where showtime_id = ? and member_id = ?",
            Long::class.java,
            showtime.showtimeId,
            2L,
        )
        assertThat(loserRows).isZero()
    }

    @Test
    fun `M1_3 REDIS_LUA_OUTSIDE_TX 활성 예약이 있으면 ACTIVE_RESERVATION_EXISTS이고 새 좌석의 Redis 키는 삭제된다`() {
        val showtime = admin.newShowtime()
        val (seatA, seatB) = showtime.seatIds.getValue("VIP")
        assertThat(holdOne(showtime.showtimeId, 1, seatA, LockStrategy.REDIS_LUA_OUTSIDE_TX)).isInstanceOf(HoldOutcome.Success::class.java)

        val second = holdOne(showtime.showtimeId, 1, seatB, LockStrategy.REDIS_LUA_OUTSIDE_TX)

        assertThat(second).isEqualTo(HoldOutcome.Failure(HoldError.ActiveReservationExists))
        assertThat(redis.hasKey(redisKey(showtime.showtimeId, seatB))).isFalse()
        assertThat(reservationsOn(showtime.showtimeId, seatB)).isEmpty()
        assertThat(redis.hasKey(redisKey(showtime.showtimeId, seatA))).isTrue()
    }

    @Test
    fun `M1_3 REDIS_LUA_OUTSIDE_TX 성공 시 Redis 키 TTL은 hold TTL 이상이다`() {
        val showtime = admin.newShowtime()
        val seat = showtime.seatIds.getValue("VIP").first()
        val held = holdOne(showtime.showtimeId, 1, seat, LockStrategy.REDIS_LUA_OUTSIDE_TX) as HoldOutcome.Success

        val ttlMillis = redis.getExpire(redisKey(showtime.showtimeId, seat), TimeUnit.MILLISECONDS)

        assertThat(ttlMillis).isBetween(props.ttl.toMillis(), props.ttl.toMillis() + 2_000)
        assertThat(reservationsOn(showtime.showtimeId, seat)).containsExactly(held.result.reservationId)
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
