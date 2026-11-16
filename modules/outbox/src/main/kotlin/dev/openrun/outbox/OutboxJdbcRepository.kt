package dev.openrun.outbox

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class OutboxJdbcRepository(
    private val jdbc: JdbcTemplate,
) {
    fun append(e: OutboxEvent) {
        appendAll(listOf(e))
    }

    /** One JDBC batch; the caller's transaction (if any) covers all of them. */
    fun appendAll(events: List<OutboxEvent>) {
        if (events.isEmpty()) return
        jdbc.batchUpdate(
            """
            insert into outbox (aggregate_type, aggregate_id, event_type, event_id, payload)
            values (?, ?, ?, ?, ?::jsonb)
            """.trimIndent(),
            events.map { e -> arrayOf<Any?>(e.aggregateType, e.aggregateId, e.eventType, e.eventId, e.payload) },
        )
    }
}
