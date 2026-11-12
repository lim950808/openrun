package dev.openrun.payment

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant

data class JanitorTick(
    val inbox: Map<InboxOutcome, Int>,
    val toUnknown: Int,
    val reconciled: Int,
    val released: Int,
    val compensated: Int,
    val compensationFailed: Int,
)

/**
 * The payment janitor (ADR-0007 §5): four paths in order, each transition a conditional UPDATE with its outbox
 * event in the same transaction (via [PaymentTransitions]); PG calls happen between transactions.
 *
 * 1. inbox apply: pending webhook rows in arrival order → APPLIED · STALE · IGNORED · ORPHAN (ADR-0004 §5)
 * 2. CONFIRMING older than `confirming-timeout` → UNKNOWN
 * 3. UNKNOWN → inquire → CONFIRMED · FAILED, or left alone; UNKNOWN past `unknown-release-after` with an overdue
 *    hold → reservation PAYING → EXPIRED only (the payment stays UNKNOWN)
 * 4. compensation: cancel at the PG for confirm-expire races, at most 3 attempts
 *
 * [tick] takes `now` so tests can jump time; the scheduled run passes the clock.
 */
@Component
class PaymentJanitor(
    private val jdbc: JdbcTemplate,
    private val tx: TransactionTemplate,
    private val transitions: PaymentTransitions,
    private val pg: PgClient,
    private val props: PaymentProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val json = JsonMapper.builder().build()

    private data class InboxRow(val id: Long, val orderId: String?, val pgStatus: String?, val paymentKey: String?, val payload: String)

    @Scheduled(fixedDelayString = "\${openrun.payment.janitor-delay-ms:5000}")
    @SchedulerLock(name = "payment-janitor", lockAtMostFor = "5m", lockAtLeastFor = "1s")
    fun scheduledTick() {
        val r = tick(clock.instant())
        if (r.inbox.isNotEmpty() || r.toUnknown + r.reconciled + r.released + r.compensated + r.compensationFailed > 0) {
            log.info("payment-janitor {}", r)
        }
    }

    fun tick(now: Instant): JanitorTick {
        val inbox = applyInbox(now)
        val toUnknown = confirmingToUnknown(now)
        val (reconciled, released) = reconcileUnknown(now)
        val (compensated, failed) = compensate()
        return JanitorTick(inbox, toUnknown, reconciled, released, compensated, failed)
    }

    // 1 ---------------------------------------------------------------------------------------------------------

    private fun applyInbox(now: Instant): Map<InboxOutcome, Int> {
        val pending = jdbc.query(
            """
            select id, order_id, pg_status, payment_key, payload::text as payload
            from payment_event_inbox where applied_at is null order by id limit 500
            """.trimIndent(),
            { rs, _ -> InboxRow(rs.getLong("id"), rs.getString("order_id"), rs.getString("pg_status"), rs.getString("payment_key"), rs.getString("payload")) },
        )
        val outcomes = mutableMapOf<InboxOutcome, Int>()
        pending.forEach { row ->
            val outcome = tx.execute {
                val outcome = applyOne(row, now)
                jdbc.update("update payment_event_inbox set applied_at = now(), outcome = ? where id = ? and applied_at is null", outcome.name, row.id)
                outcome
            }!!
            outcomes.merge(outcome, 1, Int::plus)
        }
        return outcomes
    }

    private fun applyOne(row: InboxRow, now: Instant): InboxOutcome {
        val p = row.orderId?.let { transitions.byOrderId(it) } ?: return InboxOutcome.ORPHAN
        val target = PgPaymentStatus.parse(row.pgStatus)?.toInternal() ?: return InboxOutcome.IGNORED
        if (!p.status.canApplyWebhook(target)) return InboxOutcome.STALE
        val applied = when (target) {
            PaymentStatus.CONFIRMED -> {
                val approvedAt = runCatching { json.readTree(row.payload).get("data")?.instant("approvedAt") }.getOrNull()
                transitions.toConfirmed(p, row.paymentKey ?: p.paymentKey ?: "", approvedAt, now) != null
            }
            PaymentStatus.FAILED -> transitions.toFailed(p, "PG_${row.pgStatus}", now)
            PaymentStatus.CANCELLED -> transitions.toCancelled(p)
            else -> false
        }
        return if (applied) InboxOutcome.APPLIED else InboxOutcome.STALE
    }

    // 2 ---------------------------------------------------------------------------------------------------------

    private fun confirmingToUnknown(now: Instant): Int =
        jdbc.query(
            "select * from payment where status = ? and confirming_since < ? order by confirming_since limit 500",
            PaymentTransitions.paymentMapper,
            PaymentStatus.CONFIRMING.name, now.minus(props.confirmingTimeout).utc(),
        ).count { p -> tx.execute { transitions.toUnknown(p, now, "confirming timeout") }!! }

    // 3 ---------------------------------------------------------------------------------------------------------

    private fun reconcileUnknown(now: Instant): Pair<Int, Int> {
        val unknown = jdbc.query(
            "select * from payment where status = ? order by unknown_since limit 200",
            PaymentTransitions.paymentMapper,
            PaymentStatus.UNKNOWN.name,
        )
        var reconciled = 0
        var released = 0
        unknown.forEach { stale ->
            val result = pg.inquire(stale.orderId)
            tx.executeWithoutResult {
                val p = transitions.byId(stale.id)!!
                if (p.status != PaymentStatus.UNKNOWN) return@executeWithoutResult
                val settled = when (result) {
                    is InquiryResult.Found -> when (result.status) {
                        PgPaymentStatus.DONE -> transitions.toConfirmed(p, result.paymentKey ?: p.paymentKey ?: "", result.approvedAt, now) != null
                        PgPaymentStatus.ABORTED, PgPaymentStatus.EXPIRED -> transitions.toFailed(p, "PG_${result.status}", now)
                        else -> false
                    }
                    InquiryResult.NotFound -> transitions.toFailed(p, "PG_NOT_FOUND", now)
                    is InquiryResult.Unknown -> false
                }
                if (settled) {
                    reconciled++
                } else if (p.unknownSince != null && !p.unknownSince.plus(props.unknownReleaseAfter).isAfter(now)) {
                    if (transitions.payingToExpired(p.reservationId, now) != null) released++
                }
            }
        }
        return reconciled to released
    }

    // 4 ---------------------------------------------------------------------------------------------------------

    private fun compensate(): Pair<Int, Int> {
        val ids = jdbc.queryForList(
            "select payment_id from payment_compensation where done_at is null and attempts < ? order by requested_at",
            Long::class.java,
            MAX_COMPENSATION_ATTEMPTS,
        )
        var done = 0
        var failed = 0
        ids.forEach { id: Long? ->
            val p = transitions.byId(id!!)!!
            if (p.status != PaymentStatus.CONFIRMED || p.paymentKey == null) {
                jdbc.update("update payment_compensation set done_at = now() where payment_id = ? and done_at is null", id)
                return@forEach
            }
            when (val result = pg.cancel(p.paymentKey, COMPENSATION_REASON, "comp:${p.id}")) {
                is CancelResult.Cancelled, CancelResult.AlreadyCancelled -> {
                    if (tx.execute { transitions.toCancelled(p) }!!) done++
                }
                is CancelResult.Declined, is CancelResult.Unknown -> {
                    failed++
                    val error = if (result is CancelResult.Declined) result.code else (result as CancelResult.Unknown).cause
                    jdbc.update(
                        "update payment_compensation set attempts = attempts + 1, last_error = ? where payment_id = ?",
                        error.take(200), id,
                    )
                }
            }
        }
        return done to failed
    }

    companion object {
        const val MAX_COMPENSATION_ATTEMPTS = 3
        const val COMPENSATION_REASON = "hold expired before approval"
    }
}
