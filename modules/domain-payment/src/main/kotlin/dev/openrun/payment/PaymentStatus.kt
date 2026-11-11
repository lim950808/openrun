package dev.openrun.payment

/**
 * Payment state (ADR-0004). [rank] orders states so that webhooks arriving out of order
 * can never move a payment backwards (ADR-0004 §5).
 */
enum class PaymentStatus(val rank: Int) {
    REQUESTED(0),
    CONFIRMING(1),
    UNKNOWN(1),
    CONFIRMED(2),
    FAILED(2),
    CANCELLED(3),
}

/** Allowed transitions (ADR-0004 §3). */
val allowedTransitions: Map<PaymentStatus, Set<PaymentStatus>> = mapOf(
    PaymentStatus.REQUESTED to setOf(PaymentStatus.CONFIRMING, PaymentStatus.FAILED),
    PaymentStatus.CONFIRMING to setOf(PaymentStatus.CONFIRMED, PaymentStatus.FAILED, PaymentStatus.UNKNOWN),
    PaymentStatus.UNKNOWN to setOf(PaymentStatus.CONFIRMED, PaymentStatus.FAILED),
    PaymentStatus.CONFIRMED to setOf(PaymentStatus.CANCELLED),
    PaymentStatus.FAILED to emptySet(),
    PaymentStatus.CANCELLED to emptySet(),
)

fun PaymentStatus.canApplyWebhook(target: PaymentStatus): Boolean =
    target.rank > rank && target in allowedTransitions.getValue(this)
