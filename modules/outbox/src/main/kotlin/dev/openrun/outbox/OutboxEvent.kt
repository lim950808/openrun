package dev.openrun.outbox

import java.util.UUID

/** One outbox row in Debezium Outbox Event Router SMT shape. [payload] is a JSON document. */
data class OutboxEvent(
    val aggregateType: String,
    val aggregateId: String,
    val eventType: String,
    val payload: String,
    val eventId: UUID = UUID.randomUUID(),
)
