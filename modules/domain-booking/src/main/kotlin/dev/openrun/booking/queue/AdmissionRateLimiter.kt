package dev.openrun.booking.queue

import io.github.bucket4j.Bandwidth
import io.github.bucket4j.Bucket
import java.time.Duration

/** Admission rate control for the waiting queue (glossary: 입장 속도). In-process token bucket. */
class AdmissionRateLimiter(admissionsPerSecond: Long) {
    private val bucket: Bucket = Bucket.builder()
        .addLimit(
            Bandwidth.builder()
                .capacity(admissionsPerSecond)
                .refillGreedy(admissionsPerSecond, Duration.ofSeconds(1))
                .build(),
        )
        .build()

    fun tryAdmit(): Boolean = bucket.tryConsume(1)
}
