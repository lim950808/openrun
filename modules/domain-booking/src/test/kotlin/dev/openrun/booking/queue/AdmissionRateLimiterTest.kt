package dev.openrun.booking.queue

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AdmissionRateLimiterTest {
    @Test
    fun `capacity 5 admits five then rejects`() {
        val limiter = AdmissionRateLimiter(admissionsPerSecond = 5)

        val results = (1..6).map { limiter.tryAdmit() }

        assertThat(results).containsExactly(true, true, true, true, true, false)
    }
}
