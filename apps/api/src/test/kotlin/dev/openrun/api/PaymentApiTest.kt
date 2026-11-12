package dev.openrun.api

import com.jayway.jsonpath.JsonPath
import dev.openrun.booking.hold.HoldCommand
import dev.openrun.booking.hold.HoldOutcome
import dev.openrun.booking.hold.HoldService
import dev.openrun.booking.hold.IntegrityQuery
import dev.openrun.booking.hold.SeatStatus
import dev.openrun.booking.hold.SeatStatusQuery
import dev.openrun.payment.FakeFault
import dev.openrun.payment.FakePg
import dev.openrun.payment.PaymentResult
import dev.openrun.payment.PaymentService
import dev.openrun.payment.PaymentStatus
import dev.openrun.payment.PaymentView
import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.assertj.MockMvcTester
import org.springframework.test.web.servlet.assertj.MvcTestResult
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig::class)
class PaymentApiTest @Autowired constructor(
    private val mvc: MockMvcTester,
    private val admin: AdminController,
    private val holds: HoldService,
    private val payments: PaymentService,
    private val fakePg: FakePg,
    private val seatStatus: SeatStatusQuery,
    private val integrity: IntegrityQuery,
    private val jdbc: JdbcTemplate,
    private val registry: MeterRegistry,
) {
    private val members = AtomicLong(System.nanoTime() % 1_000_000_000 + 3_000_000_000)

    private data class Paying(val showtimeId: Long, val seatId: Long, val reservationId: Long, val memberId: Long, val orderId: String, val amount: Int)

    /** hold (VIP 150,000) → POST /payments over HTTP. */
    private fun paying(): Paying {
        val showtime = admin.newShowtime()
        val seat = showtime.seatIds.getValue("VIP").first()
        val member = members.incrementAndGet()
        val held = holds.holdSeats(showtime.showtimeId, member, seat)
        val res = mvc.post().uri("/api/reservations/{id}/payments", held.reservationId).header("X-Member-Id", member.toString()).exchange()
        assertThat(res).hasStatusOk()
        val body = res.response.contentAsString
        return Paying(
            showtime.showtimeId, seat, held.reservationId, member,
            JsonPath.read(body, "$.orderId"), JsonPath.read(body, "$.amount"),
        )
    }

    private fun confirm(p: Paying, paymentKey: String, amount: Int = p.amount, member: Long = p.memberId): MvcTestResult =
        mvc.post().uri("/api/payments/confirm")
            .header("X-Member-Id", member.toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"orderId":"${p.orderId}","paymentKey":"$paymentKey","amount":$amount}""")
            .exchange()

    private fun authorize(p: Paying, fault: FakeFault = FakeFault.NONE) = fakePg.authorize(p.orderId, p.amount, fault).paymentKey

    private fun seat(p: Paying): SeatStatus = seatStatus.seats(p.showtimeId).single { it.seatId == p.seatId }.status

    private fun counter(name: String) = registry.find(name).counter()?.count() ?: 0.0

    @Test
    fun `M3_1 결제 시작 후 confirm 승인이면 예약 CONFIRMED와 좌석 SOLD`() {
        val p = paying()
        assertThat(p.orderId).isEqualTo("or-${p.reservationId}-1")
        assertThat(p.amount).isEqualTo(150_000)
        assertThat(jdbc.reservationStatus(p.reservationId)).isEqualTo("PAYING")

        val res = confirm(p, authorize(p))

        assertThat(res).hasStatusOk()
        assertThat(res).bodyJson().extractingPath("$.status").isEqualTo("CONFIRMED")
        assertThat(res).bodyJson().extractingPath("$.reservationStatus").isEqualTo("CONFIRMED")
        assertThat(seat(p)).isEqualTo(SeatStatus.SOLD)
        assertThat(jdbc.outboxEvents("payment", jdbc.paymentId(p.orderId)))
            .containsExactly("PaymentRequested", "PaymentConfirming", "PaymentConfirmed")
        assertThat(jdbc.outboxEvents("reservation", p.reservationId))
            .containsExactly("ReservationHeld", "ReservationPaymentStarted", "ReservationConfirmed")
        val view = mvc.get().uri("/api/reservations/{id}", p.reservationId).header("X-Member-Id", p.memberId.toString()).exchange()
        assertThat(view).hasStatusOk().bodyJson().extractingPath("$.payment.status").isEqualTo("CONFIRMED")
        assertThat(view).bodyJson().extractingPath("$.status").isEqualTo("CONFIRMED")
    }

    @Test
    fun `M3_1 결제 시작은 활성 결제가 있으면 같은 orderId를 다시 준다`() {
        val p = paying()
        val again = mvc.post().uri("/api/reservations/{id}/payments", p.reservationId).header("X-Member-Id", p.memberId.toString())
        assertThat(again).hasStatusOk().bodyJson().extractingPath("$.orderId").isEqualTo(p.orderId)
        assertThat(jdbc.queryForObject("select count(*) from payment where reservation_id = ?", Long::class.java, p.reservationId)).isEqualTo(1)
    }

    @Test
    fun `M3_1 결제 중인 좌석은 만료 시각이 지나도 다른 회원이 잡지 못한다`() {
        val p = paying()
        jdbc.expireHold(p.reservationId)

        assertThat(seat(p)).isEqualTo(SeatStatus.HELD)
        val other = holds.hold(HoldCommand(p.showtimeId, members.incrementAndGet(), listOf(p.seatId)))
        assertThat(other).isEqualTo(HoldOutcome.Failure(dev.openrun.booking.hold.HoldError.SeatTaken))
    }

    @Test
    fun `M3_2 금액을 바꾼 confirm은 400이고 PG를 부르지 않으며 REQUESTED와 hold가 유지된다`() {
        val p = paying()
        val key = authorize(p)
        val before = counter(PaymentService.AMOUNT_MISMATCH)

        assertThat(confirm(p, key, amount = p.amount - 140_000))
            .hasStatus(HttpStatus.BAD_REQUEST)
            .bodyJson().extractingPath("$.code").isEqualTo("AMOUNT_MISMATCH")

        assertThat(fakePg.confirmCalls(p.orderId)).isZero()
        assertThat(jdbc.paymentStatus(p.orderId)).isEqualTo("REQUESTED")
        assertThat(jdbc.reservationStatus(p.reservationId)).isEqualTo("PAYING")
        assertThat(seat(p)).isEqualTo(SeatStatus.HELD)
        assertThat(counter(PaymentService.AMOUNT_MISMATCH)).isEqualTo(before + 1)
        // The genuine amount still goes through.
        assertThat(confirm(p, key)).hasStatusOk()
    }

    @Test
    fun `M3_3 hold 만료 후 confirm은 409 HOLD_EXPIRED와 만료 연쇄`() {
        val p = paying()
        val key = authorize(p)
        jdbc.expireHold(p.reservationId)

        assertThat(confirm(p, key)).hasStatus(HttpStatus.CONFLICT).bodyJson().extractingPath("$.code").isEqualTo("HOLD_EXPIRED")

        assertThat(fakePg.confirmCalls(p.orderId)).isZero()
        assertThat(jdbc.paymentStatus(p.orderId)).isEqualTo("FAILED")
        assertThat(jdbc.reservationStatus(p.reservationId)).isEqualTo("EXPIRED")
        assertThat(seat(p)).isEqualTo(SeatStatus.AVAILABLE)
        assertThat(jdbc.outboxEvents("payment", jdbc.paymentId(p.orderId))).containsExactly("PaymentRequested", "PaymentFailed")
        assertThat(jdbc.outboxEvents("reservation", p.reservationId).last()).isEqualTo("ReservationExpired")
        // EXPIRED → PAYING is not a transition.
        assertThat(mvc.post().uri("/api/reservations/{id}/payments", p.reservationId).header("X-Member-Id", p.memberId.toString()))
            .hasStatus(HttpStatus.CONFLICT).bodyJson().extractingPath("$.code").isEqualTo("ILLEGAL_TRANSITION")
        assertThat(holds.hold(HoldCommand(p.showtimeId, members.incrementAndGet(), listOf(p.seatId)))).isInstanceOf(HoldOutcome.Success::class.java)
    }

    @Test
    fun `M3_3 만료된 HOLDING에서 결제 시작은 409 HOLD_EXPIRED`() {
        val showtime = admin.newShowtime()
        val member = members.incrementAndGet()
        val held = holds.holdSeats(showtime.showtimeId, member, showtime.seatIds.getValue("S").first())
        jdbc.expireHold(held.reservationId)

        assertThat(mvc.post().uri("/api/reservations/{id}/payments", held.reservationId).header("X-Member-Id", member.toString()))
            .hasStatus(HttpStatus.CONFLICT).bodyJson().extractingPath("$.code").isEqualTo("HOLD_EXPIRED")
        assertThat(jdbc.reservationStatus(held.reservationId)).isEqualTo("HOLDING")
    }

    @Test
    fun `M3_7 같은 confirm 동시 두 번은 PG 호출 1회이고 응답이 같다`() {
        val p = paying()
        val key = authorize(p)
        val replaysBefore = counter(PaymentService.IDEMPOTENT_REPLAY)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val futures = (1..2).map {
            pool.submit<PaymentResult<PaymentView>> {
                start.await()
                payments.confirm(p.orderId, key, p.amount, p.memberId)
            }
        }
        start.countDown()
        val views = futures.map { (it.get(10, TimeUnit.SECONDS) as PaymentResult.Success).value }
        pool.shutdown()

        assertThat(fakePg.confirmCalls(p.orderId)).isEqualTo(1)
        assertThat(views.map { it.status }).allMatch { it == PaymentStatus.CONFIRMED || it == PaymentStatus.CONFIRMING }
        assertThat(views.map { it.status }).contains(PaymentStatus.CONFIRMED)
        assertThat(counter(PaymentService.IDEMPOTENT_REPLAY)).isEqualTo(replaysBefore + 1)
        // A later replay returns the same body as the first response.
        val first = confirm(p, key)
        val second = confirm(p, key)
        assertThat(first).hasStatusOk()
        assertThat(second.response.contentAsString).isEqualTo(first.response.contentAsString)
        assertThat(fakePg.confirmCalls(p.orderId)).isEqualTo(1)
    }

    @Test
    fun `M3_8 확정된 예약을 공연 전 취소하면 CANCELLED와 좌석 복귀`() {
        val p = paying()
        assertThat(confirm(p, authorize(p))).hasStatusOk()

        val res = mvc.post().uri("/api/reservations/{id}/cancel", p.reservationId).header("X-Member-Id", p.memberId.toString()).exchange()

        assertThat(res).hasStatusOk().bodyJson().extractingPath("$.status").isEqualTo("CANCELLED")
        assertThat(res).bodyJson().extractingPath("$.reservationStatus").isEqualTo("CANCELLED")
        assertThat(seat(p)).isEqualTo(SeatStatus.AVAILABLE)
        assertThat(jdbc.outboxEvents("payment", jdbc.paymentId(p.orderId)).last()).isEqualTo("PaymentCancelled")
        assertThat(jdbc.outboxEvents("reservation", p.reservationId).last()).isEqualTo("ReservationCancelled")
        assertThat(holds.hold(HoldCommand(p.showtimeId, members.incrementAndGet(), listOf(p.seatId)))).isInstanceOf(HoldOutcome.Success::class.java)
        assertThat(integrity.check(p.showtimeId).oversellCount).isZero()
        assertThat(mvc.post().uri("/api/reservations/{id}/cancel", p.reservationId).header("X-Member-Id", p.memberId.toString()))
            .hasStatus(HttpStatus.CONFLICT).bodyJson().extractingPath("$.code").isEqualTo("ILLEGAL_TRANSITION")
    }

    @Test
    fun `M3_8 공연 시작 후 취소는 409 SHOW_STARTED`() {
        val p = paying()
        assertThat(confirm(p, authorize(p))).hasStatusOk()
        jdbc.update("update showtime set starts_at = now() - interval '1 minute' where id = ?", p.showtimeId)

        assertThat(mvc.post().uri("/api/reservations/{id}/cancel", p.reservationId).header("X-Member-Id", p.memberId.toString()))
            .hasStatus(HttpStatus.CONFLICT).bodyJson().extractingPath("$.code").isEqualTo("SHOW_STARTED")
        assertThat(jdbc.reservationStatus(p.reservationId)).isEqualTo("CONFIRMED")
    }

    @Test
    fun `M3_1 카드 거절이면 FAILED, 예약은 HOLDING으로 돌아와 새 attempt로 재시도한다`() {
        val p = paying()

        assertThat(confirm(p, authorize(p, FakeFault.DECLINE)))
            .hasStatusOk().bodyJson().extractingPath("$.failureCode").isEqualTo("REJECT_CARD_COMPANY")
        assertThat(jdbc.paymentStatus(p.orderId)).isEqualTo("FAILED")
        assertThat(jdbc.reservationStatus(p.reservationId)).isEqualTo("HOLDING")
        assertThat(seat(p)).isEqualTo(SeatStatus.HELD)

        val retry = payments.started(p.reservationId, p.memberId)
        assertThat(retry.orderId).isEqualTo("or-${p.reservationId}-2")
        val second = p.copy(orderId = retry.orderId)
        assertThat(confirm(second, authorize(second))).hasStatusOk().bodyJson().extractingPath("$.status").isEqualTo("CONFIRMED")
    }

    @Test
    fun `소유자가 아니면 403 NOT_OWNER, 없는 orderId는 404`() {
        val p = paying()
        val key = authorize(p)

        assertThat(confirm(p, key, member = p.memberId + 1)).hasStatus(HttpStatus.FORBIDDEN)
            .bodyJson().extractingPath("$.code").isEqualTo("NOT_OWNER")
        assertThat(mvc.get().uri("/api/reservations/{id}", p.reservationId).header("X-Member-Id", (p.memberId + 1).toString()))
            .hasStatus(HttpStatus.FORBIDDEN)
        assertThat(confirm(p.copy(orderId = "or-0-1"), key)).hasStatus(HttpStatus.NOT_FOUND)
            .bodyJson().extractingPath("$.code").isEqualTo("PAYMENT_NOT_FOUND")
        assertThat(mvc.get().uri("/api/payments/config")).hasStatusOk().bodyJson().extractingPath("$.pg").isEqualTo("fake")
    }
}
