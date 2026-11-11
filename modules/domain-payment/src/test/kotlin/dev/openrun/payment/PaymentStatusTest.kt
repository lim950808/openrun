package dev.openrun.payment

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PaymentStatusTest {
    @Test
    fun `FAILED cannot become CANCELLED`() {
        assertThat(PaymentStatus.FAILED.canApplyWebhook(PaymentStatus.CANCELLED)).isFalse()
    }

    @Test
    fun `REQUESTED cannot skip to CONFIRMED`() {
        assertThat(PaymentStatus.REQUESTED.canApplyWebhook(PaymentStatus.CONFIRMED)).isFalse()
    }

    @Test
    fun `CONFIRMING can become CONFIRMED`() {
        assertThat(PaymentStatus.CONFIRMING.canApplyWebhook(PaymentStatus.CONFIRMED)).isTrue()
    }

    @Test
    fun `UNKNOWN can become FAILED`() {
        assertThat(PaymentStatus.UNKNOWN.canApplyWebhook(PaymentStatus.FAILED)).isTrue()
    }

    @Test
    fun `every status has a transition row`() {
        assertThat(allowedTransitions.keys).containsExactlyInAnyOrder(*PaymentStatus.entries.toTypedArray())
    }
}
