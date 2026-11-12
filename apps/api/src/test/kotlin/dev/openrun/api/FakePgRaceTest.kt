package dev.openrun.api

import dev.openrun.booking.hold.HoldCommand
import dev.openrun.booking.hold.HoldError
import dev.openrun.booking.hold.HoldExpiryJanitor
import dev.openrun.booking.hold.HoldOutcome
import dev.openrun.booking.hold.HoldService
import dev.openrun.booking.hold.IntegrityQuery
import dev.openrun.booking.hold.SeatStatus
import dev.openrun.booking.hold.SeatStatusQuery
import dev.openrun.payment.FakeFault
import dev.openrun.payment.FakePg
import dev.openrun.payment.PaymentIntegrityQuery
import dev.openrun.payment.PaymentJanitor
import dev.openrun.payment.PaymentResult
import dev.openrun.payment.PaymentService
import dev.openrun.payment.PaymentStatus
import dev.openrun.payment.PaymentTransitions
import dev.openrun.payment.PaymentView
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Fake PG fault injection (ADR-0007 §2, ADR-0004 증명 (a)~(c)). Time is moved by passing `now` to
 * [PaymentJanitor.tick] and by moving `reservation.expires_at` with SQL; test properties shorten
 * confirming-timeout to 2 s and unknown-release-after to 5 s.
 */
@SpringBootTest
@Import(TestcontainersConfig::class)
class FakePgRaceTest @Autowired constructor(
    private val admin: AdminController,
    private val holds: HoldService,
    private val payments: PaymentService,
    private val transitions: PaymentTransitions,
    private val fakePg: FakePg,
    private val janitor: PaymentJanitor,
    private val holdJanitor: HoldExpiryJanitor,
    private val seatStatus: SeatStatusQuery,
    private val integrity: IntegrityQuery,
    private val paymentIntegrity: PaymentIntegrityQuery,
    private val jdbc: JdbcTemplate,
    private val tx: TransactionTemplate,
) {
    private val members = AtomicLong(System.nanoTime() % 1_000_000_000 + 4_000_000_000)

    private data class Paying(val showtimeId: Long, val seatId: Long, val reservationId: Long, val memberId: Long, val orderId: String, val amount: Int)

    private fun paying(): Paying {
        val showtime = admin.newShowtime()
        val seat = showtime.seatIds.getValue("R").first()
        val member = members.incrementAndGet()
        val held = holds.holdSeats(showtime.showtimeId, member, seat)
        val start = payments.started(held.reservationId, member)
        return Paying(showtime.showtimeId, seat, held.reservationId, member, start.orderId, start.amount)
    }

    private fun confirm(p: Paying, paymentKey: String): PaymentView =
        (payments.confirm(p.orderId, paymentKey, p.amount, p.memberId) as PaymentResult.Success).value

    private fun seat(p: Paying): SeatStatus = seatStatus.seats(p.showtimeId).single { it.seatId == p.seatId }.status

    @Test
    fun `M3_3b confirm 응답이 hold 만료 뒤에 와도 CONFIRMED이고 오버셀 0`() {
        val p = paying()
        val key = fakePg.authorize(p.orderId, p.amount, FakeFault.DELAY, delaySeconds = 2).paymentKey

        val inFlight = CompletableFuture.supplyAsync { confirm(p, key) }
        Thread.sleep(500)
        assertThat(jdbc.paymentStatus(p.orderId)).isEqualTo("CONFIRMING")
        jdbc.expireHold(p.reservationId)
        holdJanitor.sweep()
        // PAYING + CONFIRMING: the janitor leaves it, the seat stays HELD and nobody else can take it.
        assertThat(jdbc.reservationStatus(p.reservationId)).isEqualTo("PAYING")
        assertThat(seat(p)).isEqualTo(SeatStatus.HELD)
        assertThat(holds.hold(HoldCommand(p.showtimeId, members.incrementAndGet(), listOf(p.seatId))))
            .isEqualTo(HoldOutcome.Failure(HoldError.SeatTaken))

        val view = inFlight.get(10, TimeUnit.SECONDS)

        assertThat(view.status).isEqualTo(PaymentStatus.CONFIRMED)
        assertThat(view.reservationStatus).isEqualTo("CONFIRMED")
        assertThat(seat(p)).isEqualTo(SeatStatus.SOLD)
        assertThat(integrity.check(p.showtimeId).oversellCount).isZero()
        assertThat(integrity.check(p.showtimeId).oversellOccurredCount).isZero()
        assertThat(paymentIntegrity.check(p.showtimeId).confirmExpireRaceOccurred).isZero()
    }

    @Test
    fun `M3_3 hold janitor는 REQUESTED인 PAYING 예약을 FAILED 먼저, 그 다음 EXPIRED로 바꾼다`() {
        val p = paying()
        jdbc.expireHold(p.reservationId)

        assertThat(holdJanitor.sweep()).isGreaterThanOrEqualTo(1)

        assertThat(jdbc.paymentStatus(p.orderId)).isEqualTo("FAILED")
        assertThat(jdbc.reservationStatus(p.reservationId)).isEqualTo("EXPIRED")
        assertThat(seat(p)).isEqualTo(SeatStatus.AVAILABLE)
        assertThat(jdbc.outboxEvents("payment", jdbc.paymentId(p.orderId))).containsExactly("PaymentRequested", "PaymentFailed")
        assertThat(jdbc.outboxEvents("reservation", p.reservationId).last()).isEqualTo("ReservationExpired")
        assertThat(paymentIntegrity.check(p.showtimeId).stateEventMismatchCount).isZero()
    }

    @Test
    fun `M3_4 confirm 타임아웃이면 UNKNOWN, janitor 재조회로 CONFIRMED`() {
        val p = paying()
        val key = fakePg.authorize(p.orderId, p.amount, FakeFault.TIMEOUT).paymentKey

        val view = confirm(p, key)
        assertThat(view.status).isEqualTo(PaymentStatus.UNKNOWN)
        assertThat(view.reservationStatus).isEqualTo("PAYING")
        assertThat(seat(p)).isEqualTo(SeatStatus.HELD)

        janitor.tick(Instant.now())

        assertThat(jdbc.paymentStatus(p.orderId)).isEqualTo("CONFIRMED")
        assertThat(jdbc.reservationStatus(p.reservationId)).isEqualTo("CONFIRMED")
        assertThat(jdbc.outboxEvents("payment", jdbc.paymentId(p.orderId)))
            .containsExactly("PaymentRequested", "PaymentConfirming", "PaymentUnknown", "PaymentConfirmed")
        val m = paymentIntegrity.check(p.showtimeId)
        assertThat(m.unknownCount).isEqualTo(1)
        assertThat(m.unknownDwellP99Seconds).isNotNull().isLessThan(60.0)
        assertThat(m.stateEventMismatchCount).isZero()
    }

    @Test
    fun `M3_4 CONFIRMING에 머문 결제는 timeout 뒤 UNKNOWN, PG에 없으면 FAILED로 확정되고 예약은 HOLDING`() {
        val p = paying()
        // The app "died" after committing CONFIRMING: the PG never saw this order.
        tx.executeWithoutResult { transitions.requestedToConfirming(transitions.byOrderId(p.orderId)!!, Instant.now()) }

        val tick = janitor.tick(Instant.now().plus(Duration.ofSeconds(3)))

        assertThat(tick.toUnknown).isGreaterThanOrEqualTo(1)
        assertThat(jdbc.paymentStatus(p.orderId)).isEqualTo("FAILED")
        assertThat(jdbc.reservationStatus(p.reservationId)).isEqualTo("HOLDING")
        assertThat(seat(p)).isEqualTo(SeatStatus.HELD)
    }

    @Test
    fun `M3_5 재조회가 5분 넘게 실패해 좌석을 푼 뒤 승인이 확인되면 보상 취소하고 잔존 0`() {
        val p = paying()
        val key = fakePg.authorize(p.orderId, p.amount, FakeFault.TIMEOUT_THEN_INQUIRY_DOWN, inquiryDownSeconds = 2).paymentKey
        val t0 = Instant.now()

        assertThat(confirm(p, key).status).isEqualTo(PaymentStatus.UNKNOWN)
        janitor.tick(t0.plusSeconds(31))
        assertThat(jdbc.paymentStatus(p.orderId)).isEqualTo("UNKNOWN")
        assertThat(jdbc.reservationStatus(p.reservationId)).isEqualTo("PAYING")

        // Hold runs out; UNKNOWN past the release window → seats released, payment stays UNKNOWN.
        jdbc.expireHold(p.reservationId)
        janitor.tick(t0.plus(Duration.ofMinutes(6)))
        assertThat(jdbc.paymentStatus(p.orderId)).isEqualTo("UNKNOWN")
        assertThat(jdbc.reservationStatus(p.reservationId)).isEqualTo("EXPIRED")
        assertThat(seat(p)).isEqualTo(SeatStatus.AVAILABLE)

        // Inquiry comes back up and says DONE → CONFIRMED + CompensationRequested → cancelled in the same tick.
        Thread.sleep(2_100)
        val tick = janitor.tick(t0.plus(Duration.ofMinutes(7)))

        assertThat(tick.compensated).isGreaterThanOrEqualTo(1)
        assertThat(jdbc.paymentStatus(p.orderId)).isEqualTo("CANCELLED")
        assertThat(jdbc.reservationStatus(p.reservationId)).isEqualTo("EXPIRED")
        assertThat(jdbc.outboxEvents("payment", jdbc.paymentId(p.orderId))).containsExactly(
            "PaymentRequested", "PaymentConfirming", "PaymentUnknown", "PaymentConfirmed", "CompensationRequested", "PaymentCancelled",
        )
        val m = paymentIntegrity.check(p.showtimeId)
        assertThat(m.confirmExpireRaceOccurred).isEqualTo(1)
        assertThat(m.confirmExpireRaceRemaining).isZero()
        assertThat(m.compensationFailedCount).isZero()
        assertThat(m.stateEventMismatchCount).isZero()
        assertThat(integrity.check(p.showtimeId).oversellCount).isZero()
    }

    @Test
    fun `M3_5 보상 전에는 잔존 1로 보인다`() {
        val p = paying()
        val key = fakePg.authorize(p.orderId, p.amount, FakeFault.TIMEOUT).paymentKey
        assertThat(confirm(p, key).status).isEqualTo(PaymentStatus.UNKNOWN)
        jdbc.expireHold(p.reservationId)
        // Release the reservation without reconciling first: simulate the 5-minute path directly.
        tx.executeWithoutResult { transitions.payingToExpired(p.reservationId, Instant.now()) }
        tx.executeWithoutResult {
            val payment = transitions.byOrderId(p.orderId)!!
            transitions.toConfirmed(payment, key, Instant.now(), Instant.now())
        }

        val m = paymentIntegrity.check(p.showtimeId)
        assertThat(m.confirmExpireRaceOccurred).isEqualTo(1)
        assertThat(m.confirmExpireRaceRemaining).isEqualTo(1)

        janitor.tick(Instant.now())
        assertThat(paymentIntegrity.check(p.showtimeId).confirmExpireRaceRemaining).isZero()
    }
}
