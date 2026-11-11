package dev.openrun.booking.hold

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("openrun.hold")
data class HoldProperties(
    /** Strategy used when the request does not (or may not) choose one. */
    val strategy: LockStrategy = LockStrategy.CONDITIONAL_UPDATE,
    /** Honor the per-request `X-Hold-Strategy` override. Benchmarks only. */
    val allowStrategyOverride: Boolean = false,
    val ttl: Duration = Duration.ofMinutes(12),
    /** NAIVE only: pause between the unlocked read and the write to widen the race window. */
    val naiveDelayMs: Long = 0,
    val janitorDelayMs: Long = 5000,
)
