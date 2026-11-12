package dev.openrun.payment

import java.time.Instant

/**
 * PG adapter (ADR-0007 §1). Every call returns one of a few sealed outcomes, never throws: the transition table
 * (ADR-0004 §3) has exactly one row per outcome. Calls happen outside any database transaction.
 */
interface PgClient {
    fun confirm(paymentKey: String, orderId: String, amount: Int, idempotencyKey: String): ConfirmResult

    fun inquire(orderId: String): InquiryResult

    fun cancel(paymentKey: String, reason: String, idempotencyKey: String): CancelResult
}

sealed interface ConfirmResult {
    data class Approved(val paymentKey: String, val approvedAt: Instant?, val totalAmount: Int) : ConfirmResult

    /** Definite failure: no money moved. */
    data class Declined(val code: String, val message: String) : ConfirmResult

    /** Money may or may not have moved; only a later inquiry can tell. */
    data class Unknown(val cause: String) : ConfirmResult
}

sealed interface InquiryResult {
    data class Found(val status: PgPaymentStatus, val paymentKey: String?, val approvedAt: Instant?, val totalAmount: Int) : InquiryResult

    /** The PG has no payment for this orderId: confirm never reached it, so FAILED is definite. */
    data object NotFound : InquiryResult

    data class Unknown(val cause: String) : InquiryResult
}

sealed interface CancelResult {
    data class Cancelled(val canceledAt: Instant?) : CancelResult

    /** Treated as a successful compensation. */
    data object AlreadyCancelled : CancelResult

    data class Declined(val code: String) : CancelResult

    data class Unknown(val cause: String) : CancelResult
}

/** Toss `Payment.status` values. */
enum class PgPaymentStatus {
    READY,
    IN_PROGRESS,
    WAITING_FOR_DEPOSIT,
    DONE,
    CANCELED,
    PARTIAL_CANCELED,
    ABORTED,
    EXPIRED,
    ;

    /** ADR-0004 §5 mapping. null = not applied (recorded as IGNORED). */
    fun toInternal(): PaymentStatus? = when (this) {
        READY, IN_PROGRESS, WAITING_FOR_DEPOSIT -> null
        DONE -> PaymentStatus.CONFIRMED
        ABORTED, EXPIRED -> PaymentStatus.FAILED
        CANCELED, PARTIAL_CANCELED -> PaymentStatus.CANCELLED
    }

    companion object {
        fun parse(value: String?): PgPaymentStatus? = entries.firstOrNull { it.name == value }
    }
}
