package dev.openrun.booking.queue

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jose.crypto.MACVerifier
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.security.MessageDigest
import java.text.ParseException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.UUID

enum class TokenType(val claim: String) {
    VISITOR("visitor"),
    ACCESS("access"),
}

data class MintedToken(val token: String, val expiresAt: Instant)

sealed interface TokenCheck {
    data class Valid(val memberId: Long, val showtimeId: Long, val expiresAt: Instant) : TokenCheck

    data object Expired : TokenCheck

    /** Unparseable, wrong signature or algorithm, wrong `typ`, or a different showtime. */
    data object Invalid : TokenCheck
}

/**
 * HS256 JWTs for the waiting queue (ADR-0006 §3): visitor token (`typ=visitor`) and access token (`typ=access`,
 * with `jti`). Verification is stateless: signature, `typ`, `stid`, `exp`. No Redis.
 *
 * HS256 needs a key of at least 256 bits. A configured secret shorter than 32 bytes is stretched with SHA-256
 * (the digest becomes the key), so a short dev secret still works; production secrets should be 32+ random bytes.
 */
class TokenCodec(
    secret: String,
    private val visitorTtl: Duration,
    private val accessTtl: Duration,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val key: ByteArray = secret.toByteArray(Charsets.UTF_8).let {
        if (it.size >= MIN_KEY_BYTES) it else MessageDigest.getInstance("SHA-256").digest(it)
    }
    private val signer = MACSigner(key)
    private val verifier = MACVerifier(key)

    fun mintVisitor(memberId: Long, showtimeId: Long): MintedToken = mint(TokenType.VISITOR, memberId, showtimeId, visitorTtl)

    fun mintAccess(memberId: Long, showtimeId: Long): MintedToken = mint(TokenType.ACCESS, memberId, showtimeId, accessTtl)

    /** [showtimeId] null accepts a token for any showtime (identity only, e.g. cancelling a reservation). */
    fun verify(token: String, expected: TokenType, showtimeId: Long?): TokenCheck {
        val jwt = parse(token) ?: return TokenCheck.Invalid
        if (jwt.header.algorithm != JWSAlgorithm.HS256 || !jwt.verify(verifier)) return TokenCheck.Invalid
        val claims = jwt.jwtClaimsSet
        val type = claims.getClaim(TYPE_CLAIM) as? String
        val stid = (claims.getClaim(SHOWTIME_CLAIM) as? Number)?.toLong()
        val memberId = claims.subject?.toLongOrNull()
        val exp = claims.expirationTime?.toInstant()
        if (type != expected.claim || stid == null || memberId == null || exp == null) return TokenCheck.Invalid
        if (showtimeId != null && stid != showtimeId) return TokenCheck.Invalid
        if (!clock.instant().isBefore(exp)) return TokenCheck.Expired
        return TokenCheck.Valid(memberId, stid, exp)
    }

    private fun mint(type: TokenType, memberId: Long, showtimeId: Long, ttl: Duration): MintedToken {
        // JWT NumericDate has second precision; truncate so expiresAt equals what verify() reads back.
        val now = clock.instant().truncatedTo(ChronoUnit.SECONDS)
        val expiresAt = now.plus(ttl)
        val claims = JWTClaimsSet.Builder()
            .claim(TYPE_CLAIM, type.claim)
            .subject(memberId.toString())
            .claim(SHOWTIME_CLAIM, showtimeId)
            .issueTime(Date.from(now))
            .expirationTime(Date.from(expiresAt))
            .apply { if (type == TokenType.ACCESS) jwtID(UUID.randomUUID().toString()) }
            .build()
        val jwt = SignedJWT(JWSHeader(JWSAlgorithm.HS256), claims)
        jwt.sign(signer)
        return MintedToken(jwt.serialize(), expiresAt)
    }

    /** The only exception boundary: nimbus reports malformed input by throwing. */
    private fun parse(token: String): SignedJWT? =
        try {
            SignedJWT.parse(token).also { it.jwtClaimsSet }
        } catch (e: ParseException) {
            null
        }

    companion object {
        private const val MIN_KEY_BYTES = 32
        private const val TYPE_CLAIM = "typ"
        private const val SHOWTIME_CLAIM = "stid"
    }
}
