package dev.openrun.api

import dev.openrun.booking.hold.HoldCommand
import dev.openrun.booking.hold.HoldOutcome
import dev.openrun.booking.hold.HoldResult
import dev.openrun.booking.hold.HoldService
import dev.openrun.payment.PaymentResult
import dev.openrun.payment.PaymentStart
import dev.openrun.payment.PaymentService
import org.springframework.jdbc.core.JdbcTemplate

/** Payment test helpers: holds go through [HoldService] directly (payment APIs do not need the access token). */
fun HoldService.holdSeats(showtimeId: Long, memberId: Long, vararg seatIds: Long): HoldResult =
    (hold(HoldCommand(showtimeId, memberId, seatIds.toList())) as HoldOutcome.Success).result

fun PaymentService.started(reservationId: Long, memberId: Long): PaymentStart =
    (start(reservationId, memberId) as PaymentResult.Success).value

/** Simulates the 12-minute hold running out (tests do not wait for the TTL). */
fun JdbcTemplate.expireHold(reservationId: Long) {
    update("update reservation set expires_at = now() - interval '1 second' where id = ?", reservationId)
}

fun JdbcTemplate.reservationStatus(id: Long): String =
    queryForObject("select status from reservation where id = ?", String::class.java, id)!!

fun JdbcTemplate.paymentStatus(orderId: String): String =
    queryForObject("select status from payment where order_id = ?", String::class.java, orderId)!!

fun JdbcTemplate.outboxEvents(aggregateType: String, aggregateId: Long): List<String> =
    queryForList(
        "select event_type from outbox where aggregate_type = ? and aggregate_id = ? order by id",
        String::class.java,
        aggregateType,
        aggregateId.toString(),
    ).map { it!! }

fun JdbcTemplate.paymentId(orderId: String): Long =
    queryForObject("select id from payment where order_id = ?", Long::class.java, orderId)!!
