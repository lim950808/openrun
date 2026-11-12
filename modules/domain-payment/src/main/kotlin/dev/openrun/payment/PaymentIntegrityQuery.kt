package dev.openrun.payment

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

/** M3 verification numbers for one showtime. Names follow docs/metrics.md "M3 결제". */
data class PaymentIntegrity(
    /** Reservations that reached CONFIRMED (now CONFIRMED, or CANCELLED with a cancelled payment) / reservations. */
    val holdToConfirmRate: Double?,
    /** Payments that hit the confirm-expire race: one `payment_compensation` row per CompensationRequested event. */
    val confirmExpireRaceOccurred: Long,
    /** Payment CONFIRMED with reservation EXPIRED now, excluding compensations given up after 3 attempts. */
    val confirmExpireRaceRemaining: Long,
    val compensationFailedCount: Long,
    /** Payments that went through UNKNOWN (`unknown_since` is kept after leaving it). */
    val unknownCount: Long,
    /** p99 of `unknown_resolved_at - unknown_since`; still-UNKNOWN payments count up to now. */
    val unknownDwellP99Seconds: Double?,
    val webhookDuplicateCount: Long,
    val webhookStaleCount: Long,
    /** p99 of inbox `applied_at - received_at` over applied rows (DUPLICATE excluded). */
    val webhookApplyLagP99Seconds: Double?,
    /** Process-wide counter `openrun.payment.amount_mismatch` (no showtime tag). */
    val amountMismatchCount: Long,
    /** Process-wide counter `openrun.payment.idempotent_replay`. */
    val idempotentReplayCount: Long,
    /** Process-wide `openrun.payment.cancel_success / cancel_requests`; null before the first request. */
    val cancelSuccessRate: Double?,
    /**
     * Payments of this showtime whose current status has no outbox event of the matching type
     * (REQUESTED→PaymentRequested, CONFIRMING→PaymentConfirming, CONFIRMED→PaymentConfirmed, FAILED→PaymentFailed,
     * UNKNOWN→PaymentUnknown, CANCELLED→PaymentCancelled). Every transition appends its event in the same
     * transaction, so this is 0 unless a transition skipped its event (ADR-0004 invariant 4).
     */
    val stateEventMismatchCount: Long,
)

@Repository
class PaymentIntegrityQuery(
    private val jdbc: JdbcTemplate,
    private val registry: MeterRegistry,
) {
    fun check(showtimeId: Long): PaymentIntegrity {
        val reservations = jdbc.queryForMap(
            """
            select count(*) as total,
                   count(*) filter (where r.status = 'CONFIRMED'
                       or (r.status = 'CANCELLED' and exists (select 1 from payment p where p.reservation_id = r.id and p.status = 'CANCELLED')))
                       as confirmed
            from reservation r where r.showtime_id = ?
            """.trimIndent(),
            showtimeId,
        )
        val payments = jdbc.queryForMap(
            """
            select count(c.payment_id) as race_occurred,
                   count(*) filter (where p.status = 'CONFIRMED' and r.status = 'EXPIRED'
                       and not (c.payment_id is not null and c.done_at is null and c.attempts >= 3)) as race_remaining,
                   count(*) filter (where c.payment_id is not null and c.done_at is null and c.attempts >= 3) as comp_failed,
                   count(*) filter (where p.unknown_since is not null) as unknown_count,
                   percentile_cont(0.99) within group (
                       order by extract(epoch from coalesce(p.unknown_resolved_at, now()) - p.unknown_since))
                       filter (where p.unknown_since is not null) as unknown_dwell_p99_s
            from payment p
            join reservation r on r.id = p.reservation_id
            left join payment_compensation c on c.payment_id = p.id
            where r.showtime_id = ?
            """.trimIndent(),
            showtimeId,
        )
        val webhooks = jdbc.queryForMap(
            """
            select count(*) filter (where i.outcome = 'DUPLICATE') as duplicates,
                   count(*) filter (where i.outcome = 'STALE') as stale,
                   percentile_cont(0.99) within group (order by extract(epoch from i.applied_at - i.received_at))
                       filter (where i.outcome <> 'DUPLICATE') as apply_lag_p99_s
            from payment_event_inbox i
            join payment p on p.order_id = i.order_id
            join reservation r on r.id = p.reservation_id
            where r.showtime_id = ?
            """.trimIndent(),
            showtimeId,
        )
        val mismatch = jdbc.queryForObject(
            """
            with ev as (
                select distinct aggregate_id, event_type from outbox
                where aggregate_type = 'payment'
                  and event_type in ('PaymentRequested', 'PaymentConfirming', 'PaymentConfirmed', 'PaymentFailed', 'PaymentUnknown', 'PaymentCancelled')
            )
            select count(*) from payment p join reservation r on r.id = p.reservation_id
            where r.showtime_id = ?
              and not exists (
                select 1 from ev where ev.aggregate_id = p.id::text
                  and ev.event_type = case p.status
                      when 'REQUESTED' then 'PaymentRequested' when 'CONFIRMING' then 'PaymentConfirming'
                      when 'CONFIRMED' then 'PaymentConfirmed' when 'FAILED' then 'PaymentFailed'
                      when 'UNKNOWN' then 'PaymentUnknown' when 'CANCELLED' then 'PaymentCancelled' end)
            """.trimIndent(),
            Long::class.java,
            showtimeId,
        ) ?: 0

        val total = (reservations["total"] as Number).toLong()
        val cancelRequests = counter(PaymentService.CANCEL_REQUESTS)
        return PaymentIntegrity(
            holdToConfirmRate = if (total == 0L) null else (reservations["confirmed"] as Number).toDouble() / total,
            confirmExpireRaceOccurred = (payments["race_occurred"] as Number).toLong(),
            confirmExpireRaceRemaining = (payments["race_remaining"] as Number).toLong(),
            compensationFailedCount = (payments["comp_failed"] as Number).toLong(),
            unknownCount = (payments["unknown_count"] as Number).toLong(),
            unknownDwellP99Seconds = (payments["unknown_dwell_p99_s"] as Number?)?.toDouble(),
            webhookDuplicateCount = (webhooks["duplicates"] as Number).toLong(),
            webhookStaleCount = (webhooks["stale"] as Number).toLong(),
            webhookApplyLagP99Seconds = (webhooks["apply_lag_p99_s"] as Number?)?.toDouble(),
            amountMismatchCount = counter(PaymentService.AMOUNT_MISMATCH).toLong(),
            idempotentReplayCount = counter(PaymentService.IDEMPOTENT_REPLAY).toLong(),
            cancelSuccessRate = if (cancelRequests == 0.0) null else counter(PaymentService.CANCEL_SUCCESS) / cancelRequests,
            stateEventMismatchCount = mismatch,
        )
    }

    private fun counter(name: String): Double = registry.find(name).counter()?.count() ?: 0.0
}
