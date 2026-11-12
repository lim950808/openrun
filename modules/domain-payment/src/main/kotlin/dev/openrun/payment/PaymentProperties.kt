package dev.openrun.payment

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** ADR-0007 §6. Secrets come from the environment or the gitignored `.env`, never from the repo. */
@ConfigurationProperties("openrun.payment")
data class PaymentProperties(
    /** `fake` (tests, CI, weekly open run, bench) or `toss` (M3-1 live demo). */
    val pg: String = "fake",
    val toss: Toss = Toss(),
    val fakePg: FakePgProps = FakePgProps(),
    /** CONFIRMING older than this → UNKNOWN (the app died while handling the PG response). */
    val confirmingTimeout: Duration = Duration.ofSeconds(30),
    /** UNKNOWN older than this → the PAYING reservation may expire (ADR-0004 §2). */
    val unknownReleaseAfter: Duration = Duration.ofMinutes(5),
    val janitorDelayMs: Long = 5000,
) {
    data class Toss(
        val secretKey: String = "",
        val clientKey: String = "",
        val webhookSecret: String = "",
        val baseUrl: String = TossPgClient.DEFAULT_BASE_URL,
    )

    data class FakePgProps(
        /** Post a signed PAYMENT_STATUS_CHANGED(DONE) webhook after each approval. */
        val webhooks: Boolean = false,
        val webhookUrl: String = "http://localhost:8080/api/webhooks/toss",
    )
}
