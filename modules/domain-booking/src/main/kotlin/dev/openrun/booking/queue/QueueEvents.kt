package dev.openrun.booking.queue

import dev.openrun.outbox.OutboxEvent
import java.time.Instant

/** Outbox events for the `queue_admission` aggregate (ADR-0006 §5). JSON literals, like ReservationEvents. */
internal object QueueEvents {
    private const val AGGREGATE = "queue_admission"

    fun entered(rowId: Long, showtimeId: Long, memberId: Long, enteredAt: Instant) = OutboxEvent(
        aggregateType = AGGREGATE,
        aggregateId = rowId.toString(),
        eventType = "QueueEntered",
        payload = """{"showtimeId":$showtimeId,"memberId":$memberId,"enteredAt":"$enteredAt"}""",
    )

    fun admitted(rowId: Long, showtimeId: Long, memberId: Long, admittedAt: Instant, admittedSeq: Long, tokenExpiresAt: Instant) =
        OutboxEvent(
            aggregateType = AGGREGATE,
            aggregateId = rowId.toString(),
            eventType = "QueueAdmitted",
            payload = """{"showtimeId":$showtimeId,"memberId":$memberId,"admittedAt":"$admittedAt",""" +
                """"admittedSeq":$admittedSeq,"tokenExpiresAt":"$tokenExpiresAt"}""",
        )

    fun abandoned(rowId: Long, showtimeId: Long, memberId: Long, abandonedAt: Instant, lastActiveAt: Instant) = OutboxEvent(
        aggregateType = AGGREGATE,
        aggregateId = rowId.toString(),
        eventType = "QueueAbandoned",
        payload = """{"showtimeId":$showtimeId,"memberId":$memberId,"abandonedAt":"$abandonedAt","lastActiveAt":"$lastActiveAt"}""",
    )
}
