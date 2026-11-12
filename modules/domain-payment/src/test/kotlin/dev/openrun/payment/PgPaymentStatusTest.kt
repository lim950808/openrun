package dev.openrun.payment

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** ADR-0004 §5 mapping table. */
class PgPaymentStatusTest {
    @Test
    fun `M3_6 토스 상태 매핑`() {
        assertThat(PgPaymentStatus.entries.associateWith { it.toInternal() }).isEqualTo(
            mapOf(
                PgPaymentStatus.READY to null,
                PgPaymentStatus.IN_PROGRESS to null,
                PgPaymentStatus.WAITING_FOR_DEPOSIT to null,
                PgPaymentStatus.DONE to PaymentStatus.CONFIRMED,
                PgPaymentStatus.CANCELED to PaymentStatus.CANCELLED,
                PgPaymentStatus.PARTIAL_CANCELED to PaymentStatus.CANCELLED,
                PgPaymentStatus.ABORTED to PaymentStatus.FAILED,
                PgPaymentStatus.EXPIRED to PaymentStatus.FAILED,
            ),
        )
    }
}
