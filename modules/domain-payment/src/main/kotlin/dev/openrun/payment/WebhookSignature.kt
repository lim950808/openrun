package dev.openrun.payment

import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * `tosspayments-webhook-signature` (ADR-0007 intro): HMAC-SHA256 over `"{payload}:{transmissionTime}"` with the
 * webhook secret. Header = `v1:<base64>`, several comma-separated values allowed (key rotation).
 *
 * Assumption, unverified against live Toss: the value after `v1:` is base64 of the raw HMAC bytes (the docs do not
 * say whether the digest is hex-encoded first). Adjust [sign] on the first real webhook.
 */
class WebhookSignature(secret: String) {
    private val key = SecretKeySpec(secret.toByteArray(), ALGORITHM)

    fun sign(payload: String, transmissionTime: String): String = "v1:" + Base64.getEncoder().encodeToString(digest(payload, transmissionTime))

    /** Constant-time comparison against every `v1:` value in [header]. */
    fun verify(payload: String, transmissionTime: String?, header: String?): Boolean {
        if (transmissionTime.isNullOrBlank() || header.isNullOrBlank()) return false
        val expected = digest(payload, transmissionTime)
        return header.split(',').map { it.trim() }.filter { it.startsWith("v1:") }.any { value ->
            val given = runCatching { Base64.getDecoder().decode(value.removePrefix("v1:")) }.getOrNull()
            given != null && MessageDigest.isEqual(expected, given)
        }
    }

    private fun digest(payload: String, transmissionTime: String): ByteArray =
        Mac.getInstance(ALGORITHM).apply { init(key) }.doFinal("$payload:$transmissionTime".toByteArray())

    private companion object {
        const val ALGORITHM = "HmacSHA256"
    }
}
