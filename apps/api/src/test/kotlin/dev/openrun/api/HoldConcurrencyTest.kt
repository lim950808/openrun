package dev.openrun.api

import dev.openrun.booking.hold.HoldCommand
import dev.openrun.booking.hold.HoldError
import dev.openrun.booking.hold.HoldOutcome
import dev.openrun.booking.hold.HoldService
import dev.openrun.booking.hold.Integrity
import dev.openrun.booking.hold.IntegrityQuery
import dev.openrun.booking.hold.LockStrategy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * M1-3: the same seat requested by 64 members at once, per lock strategy. Calls HoldService directly so the
 * race is between database transactions, not HTTP handling. Test properties: naive-delay-ms=30, Hikari pool 48.
 */
@SpringBootTest
@Import(TestcontainersConfig::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HoldConcurrencyTest @Autowired constructor(
    private val holds: HoldService,
    private val admin: AdminController,
    private val integrity: IntegrityQuery,
    private val jdbc: JdbcTemplate,
) {
    private val threads = 64
    private val pool = Executors.newFixedThreadPool(threads)

    @AfterAll
    fun shutdown() {
        pool.shutdownNow()
    }

    private data class Race(val outcomes: List<HoldOutcome>, val errors: List<Throwable>, val integrity: Integrity) {
        val successes get() = outcomes.filterIsInstance<HoldOutcome.Success>()
    }

    private fun race(showtimeId: Long, strategy: LockStrategy, seatsFor: (Int) -> List<Long>): Race {
        val ready = CountDownLatch(threads)
        val start = CountDownLatch(1)
        val futures = (0 until threads).map { i ->
            pool.submit<HoldOutcome> {
                ready.countDown()
                start.await()
                holds.hold(HoldCommand(showtimeId, showtimeId * 1_000 + i, seatsFor(i), strategy))
            }
        }
        ready.await(10, TimeUnit.SECONDS)
        start.countDown()
        val outcomes = mutableListOf<HoldOutcome>()
        val errors = mutableListOf<Throwable>()
        futures.forEach { f -> runCatching { f.get(60, TimeUnit.SECONDS) }.fold({ outcomes += it }, { errors += it }) }
        return Race(outcomes, errors, integrity.check(showtimeId))
    }

    @Test
    fun `M1_3 NAIVE는 오버셀을 재현한다`() {
        val showtime = admin.newShowtime()
        val seat = showtime.seatIds.getValue("VIP").first()

        val race = race(showtime.showtimeId, LockStrategy.NAIVE) { listOf(seat) }

        assertThat(race.errors).isEmpty()
        assertThat(race.successes.size).isGreaterThanOrEqualTo(2)
        assertThat(race.integrity.oversellCount).isGreaterThanOrEqualTo(1)
        assertThat(race.integrity.oversellOccurredCount).isGreaterThanOrEqualTo(1)
        assertThat(race.integrity.oversellOverlapPairs).isGreaterThanOrEqualTo(1)
    }

    @ParameterizedTest(name = "M1_3 {0}는 오버셀 0건")
    @EnumSource(LockStrategy::class, names = ["NAIVE"], mode = EnumSource.Mode.EXCLUDE)
    fun `M1_3 안전한 전략은 오버셀 0건`(strategy: LockStrategy) {
        val showtime = admin.newShowtime()
        val seat = showtime.seatIds.getValue("VIP").first()

        val race = race(showtime.showtimeId, strategy) { listOf(seat) }

        assertThat(race.errors).isEmpty()
        assertThat(race.successes).hasSize(1)
        assertThat(race.outcomes.filterIsInstance<HoldOutcome.Failure>()).allMatch { it.error == HoldError.SeatTaken }
        assertThat(race.integrity.oversellCount).isZero()
        assertThat(race.integrity.oversellOccurredCount).isZero()
        assertThat(race.integrity.oversellOverlapPairs).isZero()
        assertThat(race.integrity.heldSeats).isEqualTo(1)
    }

    @ParameterizedTest(name = "M1_3 {0} 2석이 겹치는 요청도 오버셀 0건, 부분 선점 없음")
    @EnumSource(LockStrategy::class, names = ["CONDITIONAL_UPDATE", "FOR_UPDATE"])
    fun `M1_3 2석 겹침 요청`(strategy: LockStrategy) {
        val showtime = admin.newShowtime()
        val (a, b, c) = showtime.seatIds.getValue("R")

        val race = race(showtime.showtimeId, strategy) { i -> if (i % 2 == 0) listOf(a, b) else listOf(b, c) }

        assertThat(race.errors).isEmpty()
        assertThat(race.successes).hasSize(1)
        assertThat(race.integrity.oversellCount).isZero()
        assertThat(race.integrity.oversellOccurredCount).isZero()
        assertThat(race.integrity.oversellOverlapPairs).isZero()
        val seatCounts = jdbc.queryForList(
            """
            select count(rs.seat_id) as seats from reservation r
            left join reservation_seat rs on rs.reservation_id = r.id
            where r.showtime_id = ? group by r.id
            """.trimIndent(),
            Long::class.java,
            showtime.showtimeId,
        )
        assertThat(seatCounts).containsExactly(2L)
        val heldRows = jdbc.queryForList(
            "select seat_id from seat_hold where showtime_id = ? and reservation_id is not null order by seat_id",
            Long::class.java,
            showtime.showtimeId,
        )
        val won = race.successes.single().result.seats.map { it.seatId }
        assertThat(won).isIn(listOf(a, b), listOf(b, c))
        assertThat(heldRows).isEqualTo(won)
    }
}
