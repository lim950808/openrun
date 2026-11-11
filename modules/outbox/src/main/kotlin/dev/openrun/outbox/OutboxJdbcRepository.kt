package dev.openrun.outbox

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class OutboxJdbcRepository(
    private val jdbc: JdbcTemplate,
) {
    fun append(e: OutboxEvent) {
        jdbc.update(
            """
            insert into outbox (aggregate_type, aggregate_id, event_type, event_id, payload)
            values (?, ?, ?, ?, ?::jsonb)
            """.trimIndent(),
            e.aggregateType,
            e.aggregateId,
            e.eventType,
            e.eventId,
            e.payload,
        )
    }
}
