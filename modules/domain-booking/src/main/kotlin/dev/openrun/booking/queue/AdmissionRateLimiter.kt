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

    /** This tick's admission budget: takes up to [max] tokens, fewer when the bucket has not refilled yet. */
    fun takeUpTo(max: Long): Long = bucket.tryConsumeAsMuchAsPossible(max)

    /** Returns budget a tick did not use (queue ran empty), so the next tick is not short-changed. */
    fun refund(tokens: Long) {
        if (tokens > 0) bucket.addTokens(tokens)
    }
}
