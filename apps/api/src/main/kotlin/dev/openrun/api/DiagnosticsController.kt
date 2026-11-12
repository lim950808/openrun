package dev.openrun.api

import dev.openrun.booking.hold.HoldService
import dev.openrun.booking.hold.LockStrategy
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Where hold latency goes per lock strategy (`openrun.hold.phase`), plus Hikari connection wait. For bench-m1.sh.
 *
 * - `reset=true` returns the current numbers, then removes this strategy's phase timers (they are re-registered on
 *   the next hold) and restarts the pending-max window. bench-m1.sh resets before each arrival run and snapshots
 *   right after it, so the burst run and earlier runs of the same strategy are not mixed in.
 * - Hikari acquire is process-wide (no strategy tag) and its percentiles are a 2~3 min window (application.yml).
 * - `hikari_pending_max` is the max of `hikaricp.connections.pending` sampled every 100 ms since the last reset.
 */
@RestController
class DiagnosticsController(
    private val registry: MeterRegistry,
) {
    private val pendingMax = AtomicLong()

    @Scheduled(fixedDelay = 100)
    fun samplePending() {
        val pending = registry.find("hikaricp.connections.pending").gauges().sumOf { it.value() }.toLong()
        pendingMax.accumulateAndGet(pending) { a, b -> maxOf(a, b) }
    }

    @GetMapping("/api/admin/diagnostics/hold-phases")
    fun holdPhases(
        @RequestParam strategy: LockStrategy,
        @RequestParam(defaultValue = "false") reset: Boolean,
    ): Map<String, Any?> {
        val timers = registry.find(HoldService.PHASE_METRIC).tag("strategy", strategy.name).timers()
        val phases = timers.sortedBy { it.id.getTag("phase") }.associate { it.id.getTag("phase") to summary(it) }
        val acquire = registry.find("hikaricp.connections.acquire").timer()
        val result = linkedMapOf<String, Any?>(
            "strategy" to strategy.name,
            "phases" to phases,
            "hikari_acquire_p50_ms" to acquire?.let { percentileMs(it, 0.5) },
            "hikari_acquire_p99_ms" to acquire?.let { percentileMs(it, 0.99) },
            "hikari_pending_max" to if (reset) pendingMax.getAndSet(0) else pendingMax.get(),
        )
        if (reset) timers.forEach { registry.remove(it) }
        return result
    }

    private fun summary(timer: Timer): Map<String, Any?> = linkedMapOf(
        "count" to timer.count(),
        "mean_ms" to timer.mean(TimeUnit.MILLISECONDS),
        "p50_ms" to percentileMs(timer, 0.5),
        "p99_ms" to percentileMs(timer, 0.99),
    )

    private fun percentileMs(timer: Timer, percentile: Double): Double? =
        timer.takeSnapshot().percentileValues().firstOrNull { it.percentile() == percentile }?.value(TimeUnit.MILLISECONDS)
}
