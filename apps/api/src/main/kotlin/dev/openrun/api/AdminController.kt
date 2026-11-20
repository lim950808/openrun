package dev.openrun.api

import dev.openrun.booking.hold.IntegrityQuery
import dev.openrun.booking.queue.QueueIntegrityQuery
import dev.openrun.payment.PaymentIntegrityQuery
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.concurrent.TimeUnit

data class CreateShowtimeRequest(val startsAt: Instant, val openAt: Instant)

/**
 * Operator endpoints for benchmarks, tests and the demo server. Guarded by `X-Admin-Token` when
 * `openrun.admin.token` is set, open when it is blank ([AdminTokenInterceptor], ADR-0009 §3).
 */
@RestController
class AdminController(
    private val provisioner: ShowtimeProvisioner,
    private val integrity: IntegrityQuery,
    private val queueIntegrity: QueueIntegrityQuery,
    private val paymentIntegrity: PaymentIntegrityQuery,
    private val registry: MeterRegistry,
) {
    /** Showtime plus its 1,500 `seat_hold` rows; the two-step write is in [ShowtimeProvisioner] (ADR-0003 rule 2). */
    @PostMapping("/api/admin/showtimes")
    @ResponseStatus(HttpStatus.CREATED)
    fun createShowtime(@RequestBody body: CreateShowtimeRequest): CreateShowtimeResponse =
        provisioner.create(startsAt = body.startsAt, openAt = body.openAt)

    /** Verification numbers with docs/metrics.md names. */
    @GetMapping("/api/admin/showtimes/{showtimeId}/integrity")
    fun integrity(@PathVariable showtimeId: Long): Map<String, Long> {
        val i = integrity.check(showtimeId)
        return linkedMapOf(
            "oversell_count" to i.oversellCount,
            "oversell_occurred_count" to i.oversellOccurredCount,
            "oversell_overlap_pairs" to i.oversellOverlapPairs,
            "held_seats" to i.heldSeats,
            "available_seats" to i.availableSeats,
            "holding_reservations" to i.holdingReservations,
            "expired_reservations" to i.expiredReservations,
            "expire_backlog_max_s" to i.expireBacklogMaxSeconds,
        )
    }

    /**
     * M2 verification numbers with docs/metrics.md names. `unauthorized_seat_calls` is the process-wide sum of
     * `openrun.api.errors{code=ACCESS_TOKEN_*}` (not per showtime: the counter has no showtime tag).
     */
    @GetMapping("/api/admin/showtimes/{showtimeId}/queue-integrity")
    fun queueIntegrity(@PathVariable showtimeId: Long): Map<String, Any?> {
        val q = queueIntegrity.check(showtimeId)
        val unauthorized = registry.find("openrun.api.errors").counters()
            .filter { it.id.getTag("code")?.startsWith("ACCESS_TOKEN_") == true }
            .sumOf { it.count() }
            .toLong()
        return linkedMapOf(
            "entered" to q.entered,
            "admitted" to q.admitted,
            "abandoned" to q.abandoned,
            "waiting" to q.waiting,
            "lost" to q.lost,
            "abandon_rate" to q.abandonRate,
            "fifo_violation_rate" to q.fifoViolationRate,
            "admission_delay_p50_s" to q.admissionDelayP50Seconds,
            "admission_delay_p99_s" to q.admissionDelayP99Seconds,
            "admission_rate_actual" to q.admissionRateActual,
            "admission_ticks" to q.admissionTicks,
            "unauthorized_seat_calls" to unauthorized,
        )
    }

    /**
     * M3 verification numbers with docs/metrics.md names (ADR-0007 §4). Counter-backed values
     * (`amount_mismatch_count`, `idempotent_replay_count`, `cancel_success_rate`) and `webhook_ack_p99_ms` are
     * process-wide, not per showtime.
     */
    @GetMapping("/api/admin/showtimes/{showtimeId}/payment-integrity")
    fun paymentIntegrity(@PathVariable showtimeId: Long): Map<String, Any?> {
        val p = paymentIntegrity.check(showtimeId)
        val ack = registry.find(WebhookController.ACK_METRIC).timer()
        return linkedMapOf(
            "hold_to_confirm_rate" to p.holdToConfirmRate,
            "confirm_expire_race_occurred" to p.confirmExpireRaceOccurred,
            "confirm_expire_race_remaining" to p.confirmExpireRaceRemaining,
            "compensation_failed_count" to p.compensationFailedCount,
            "unknown_count" to p.unknownCount,
            "unknown_dwell_p99_s" to p.unknownDwellP99Seconds,
            "webhook_ack_p99_ms" to ack?.takeIf { it.count() > 0 }?.let { t ->
                t.takeSnapshot().percentileValues().firstOrNull { it.percentile() == 0.99 }?.value(TimeUnit.MILLISECONDS)
            },
            "webhook_apply_lag_p99_s" to p.webhookApplyLagP99Seconds,
            "webhook_duplicate_count" to p.webhookDuplicateCount,
            "webhook_stale_count" to p.webhookStaleCount,
            "amount_mismatch_count" to p.amountMismatchCount,
            "idempotent_replay_count" to p.idempotentReplayCount,
            "cancel_success_rate" to p.cancelSuccessRate,
            "state_event_mismatch_count" to p.stateEventMismatchCount,
        )
    }
}
