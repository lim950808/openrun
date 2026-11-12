package dev.openrun.api

import dev.openrun.booking.hold.HoldService
import dev.openrun.booking.hold.SeatStatus
import dev.openrun.booking.hold.SeatStatusQuery
import dev.openrun.payment.ApproveOutcome
import dev.openrun.payment.PaymentRow
import dev.openrun.payment.PaymentService
import dev.openrun.payment.PaymentStatus
import dev.openrun.payment.PaymentTransitions
import dev.openrun.payment.StartRow
import dev.openrun.payment.allowedTransitions
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * ADR-0004 §3 payment transition table against the database: every (from, to) pair through [PaymentTransitions].
 * Allowed pairs move the row and append the event; the rest match 0 rows (the API maps that to 409).
 */
@SpringBootTest
@Import(TestcontainersConfig::class)
class PaymentTransitionDbTest @Autowired constructor(
    private val admin: AdminController,
    private val holds: HoldService,
    private val payments: PaymentService,
    private val transitions: PaymentTransitions,
    private val seatStatus: SeatStatusQuery,
    private val jdbc: JdbcTemplate,
    private val tx: TransactionTemplate,
) {
    private val members = AtomicLong(System.nanoTime() % 1_000_000_000 + 6_000_000_000)

    private data class Fixture(val showtimeId: Long, val seatId: Long, val reservationId: Long, val memberId: Long, val orderId: String)

    private fun paying(): Fixture {
        val showtime = admin.newShowtime()
        val seat = showtime.seatIds.getValue("S").last()
        val member = members.incrementAndGet()
        val held = holds.holdSeats(showtime.showtimeId, member, seat)
        val start = payments.started(held.reservationId, member)
        return Fixture(showtime.showtimeId, seat, held.reservationId, member, start.orderId)
    }

    /** Puts the payment (and a matching reservation state) directly into [from]. */
    private fun at(from: PaymentStatus): Pair<Fixture, PaymentRow> {
        val f = paying()
        jdbc.update("update payment set status = ?, payment_key = 'pk' where order_id = ?", from.name, f.orderId)
        if (from == PaymentStatus.CONFIRMED || from == PaymentStatus.CANCELLED) {
            jdbc.update("update reservation set status = ? where id = ?", if (from == PaymentStatus.CONFIRMED) "CONFIRMED" else "CANCELLED", f.reservationId)
        }
        if (from == PaymentStatus.FAILED) jdbc.update("update reservation set status = 'HOLDING' where id = ?", f.reservationId)
        return f to transitions.byOrderId(f.orderId)!!
    }

    private fun apply(p: PaymentRow, to: PaymentStatus): Boolean = tx.execute {
        val now = Instant.now()
        when (to) {
            PaymentStatus.REQUESTED -> false
            PaymentStatus.CONFIRMING -> transitions.requestedToConfirming(p, now)
            PaymentStatus.UNKNOWN -> transitions.toUnknown(p, now, "test")
            PaymentStatus.CONFIRMED -> transitions.toConfirmed(p, "pk", now, now) != null
            PaymentStatus.FAILED -> transitions.toFailed(p, "TEST", now)
            PaymentStatus.CANCELLED -> transitions.toCancelled(p)
        }
    }!!

    private val eventOf = mapOf(
        PaymentStatus.CONFIRMING to "PaymentConfirming",
        PaymentStatus.UNKNOWN to "PaymentUnknown",
        PaymentStatus.CONFIRMED to "PaymentConfirmed",
        PaymentStatus.FAILED to "PaymentFailed",
        PaymentStatus.CANCELLED to "PaymentCancelled",
    )

    @Test
    fun `M3 결제 전이표의 모든 쌍 - 허용은 이동과 이벤트, 비허용은 0행`() {
        for (from in PaymentStatus.entries) {
            for (to in PaymentStatus.entries) {
                val (f, p) = at(from)
                val eventsBefore = jdbc.outboxEvents("payment", p.id)
                val allowed = to in allowedTransitions.getValue(from)

                val moved = apply(p, to)

                assertThat(moved).describedAs("$from -> $to").isEqualTo(allowed)
                val now = jdbc.paymentStatus(f.orderId)
                val events = jdbc.outboxEvents("payment", p.id)
                if (allowed) {
                    assertThat(now).describedAs("$from -> $to").isEqualTo(to.name)
                    assertThat(events.drop(eventsBefore.size).first()).describedAs("$from -> $to").isEqualTo(eventOf.getValue(to))
                } else {
                    assertThat(now).describedAs("$from -> $to").isEqualTo(from.name)
                    assertThat(events).describedAs("$from -> $to").isEqualTo(eventsBefore)
                }
            }
        }
    }

    @Test
    fun `M3_7 읽은 뒤 다른 요청이 먼저 옮기면 조건부 UPDATE는 0행`() {
        val f = paying()
        val stale = transitions.byOrderId(f.orderId)!!
        assertThat(tx.execute { transitions.requestedToConfirming(stale, Instant.now()) }).isTrue()

        assertThat(tx.execute { transitions.requestedToConfirming(stale, Instant.now()) }).isFalse()
        assertThat(tx.execute { transitions.toFailed(stale, "LATE", Instant.now()) }).isFalse()
        assertThat(jdbc.paymentStatus(f.orderId)).isEqualTo("CONFIRMING")
    }

    @Test
    fun `M3_1 HOLDING to PAYING은 좌석을 만료 시각과 무관하게 HELD로 고정한다`() {
        val f = paying()
        assertThat(jdbc.reservationStatus(f.reservationId)).isEqualTo("PAYING")
        assertThat(jdbc.queryForObject("select expires_at = 'infinity' from seat_hold where reservation_id = ?", Boolean::class.java, f.reservationId)).isTrue()
        // Second start is not a transition: 0 rows.
        assertThat(tx.execute { transitions.holdingToPaying(f.reservationId, f.memberId, Instant.now()) }).isEqualTo(StartRow.NoRow)
    }

    @Test
    fun `M3_1 결제 실패 시 미만료면 PAYING to HOLDING, 좌석 만료 시각 복원`() {
        val f = paying()
        val p = transitions.byOrderId(f.orderId)!!
        assertThat(tx.execute { transitions.toFailed(p, "TEST", Instant.now()) }).isTrue()

        assertThat(jdbc.reservationStatus(f.reservationId)).isEqualTo("HOLDING")
        assertThat(
            jdbc.queryForObject(
                "select sh.expires_at = r.expires_at from seat_hold sh join reservation r on r.id = sh.reservation_id where r.id = ?",
                Boolean::class.java,
                f.reservationId,
            ),
        ).isTrue()
        assertThat(jdbc.outboxEvents("reservation", f.reservationId).last()).isEqualTo("ReservationPaymentFailed")
    }

    @Test
    fun `M3_3 결제 실패 시 만료면 PAYING to EXPIRED, 좌석 AVAILABLE`() {
        val f = paying()
        jdbc.expireHold(f.reservationId)
        val p = transitions.byOrderId(f.orderId)!!
        assertThat(tx.execute { transitions.toFailed(p, "TEST", Instant.now()) }).isTrue()

        assertThat(jdbc.reservationStatus(f.reservationId)).isEqualTo("EXPIRED")
        assertThat(seatStatus.seats(f.showtimeId).single { it.seatId == f.seatId }.status).isEqualTo(SeatStatus.AVAILABLE)
    }

    @Test
    fun `M3_5 예약이 EXPIRED인데 승인되면 결제는 CONFIRMED로 커밋하고 보상을 적재한다`() {
        val f = paying()
        jdbc.update("update payment set status = 'UNKNOWN', unknown_since = now() where order_id = ?", f.orderId)
        jdbc.expireHold(f.reservationId)
        assertThat(tx.execute { transitions.payingToExpired(f.reservationId, Instant.now()) }).isNotNull()
        val p = transitions.byOrderId(f.orderId)!!

        assertThat(tx.execute { transitions.toConfirmed(p, "pk", Instant.now(), Instant.now()) }).isEqualTo(ApproveOutcome.COMPENSATION_REQUESTED)

        assertThat(jdbc.paymentStatus(f.orderId)).isEqualTo("CONFIRMED")
        assertThat(jdbc.reservationStatus(f.reservationId)).isEqualTo("EXPIRED")
        assertThat(jdbc.queryForObject("select count(*) from payment_compensation where payment_id = ?", Long::class.java, p.id)).isEqualTo(1)
        assertThat(jdbc.outboxEvents("payment", p.id).takeLast(2)).containsExactly("PaymentConfirmed", "CompensationRequested")
    }

    @Test
    fun `M3 활성 결제는 예약당 하나다`() {
        val f = paying()
        val inserted = runCatching {
            jdbc.update("insert into payment (reservation_id, attempt, order_id, amount, status) values (?, 99, ?, 1, 'REQUESTED')", f.reservationId, "or-${f.reservationId}-99")
        }
        assertThat(inserted.isFailure).isTrue()
    }
}
