package dev.openrun.booking.queue

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** Waiting queue settings (ADR-0006 §6). */
@ConfigurationProperties("openrun.queue")
data class QueueProperties(
    /** Admission rate per showtime (glossary 입장 속도), enforced per tick by Bucket4j. */
    val admissionsPerSecond: Long = 100,
    /** Entry opens this long before `showtime.open_at`. */
    val entryLead: Duration = Duration.ofMinutes(10),
    /** A waiting member with no poll (or entry) for this long is abandoned when the scheduler reaches them. */
    val abandonAfter: Duration = Duration.ofSeconds(30),
    val accessTokenTtl: Duration = Duration.ofMinutes(10),
    val visitorTokenTtl: Duration = Duration.ofHours(3),
    val tickMs: Long = 1000,
    /** false: seat APIs fall back to `X-Member-Id` (M1 benchmark mode). */
    val requireAccessToken: Boolean = true,
    val tokenSecret: String = "dev-only-not-a-secret-openrun-local-token-key",
)
