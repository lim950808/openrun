package dev.openrun.api

import dev.openrun.booking.queue.QueueProperties
import dev.openrun.booking.queue.TokenCheck
import dev.openrun.booking.queue.TokenCodec
import dev.openrun.booking.queue.TokenType
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component

sealed interface Caller {
    data class Member(val memberId: Long) : Caller

    /** Already counted in `openrun.api.errors`; return as is. */
    data class Rejected(val response: ResponseEntity<Any>) : Caller
}

/**
 * Who is calling, per ADR-0006 §3. Seat APIs need an access token for the showtime unless
 * `openrun.queue.require-access-token=false` (then `X-Member-Id`, the M1 behaviour). Reservation cancel accepts
 * either, because a payment may outlive the token.
 */
@Component
class CallerIdentity(
    private val props: QueueProperties,
    private val codec: TokenCodec,
    private val errors: ApiErrors,
) {
    fun forSeatApi(showtimeId: Long, authorization: String?, memberHeader: Long?): Caller {
        if (!props.requireAccessToken) return fromHeader(memberHeader)
        if (authorization == null) return reject(HttpStatus.UNAUTHORIZED, "ACCESS_TOKEN_REQUIRED")
        return fromAccessToken(authorization, showtimeId)
    }

    fun forReservation(authorization: String?, memberHeader: Long?): Caller {
        if (authorization != null) {
            val byToken = fromAccessToken(authorization, null)
            if (byToken is Caller.Member || memberHeader == null) return byToken
        }
        return fromHeader(memberHeader)
    }

    private fun fromAccessToken(authorization: String, showtimeId: Long?): Caller {
        val token = bearer(authorization) ?: return reject(HttpStatus.UNAUTHORIZED, "ACCESS_TOKEN_INVALID")
        return when (val check = codec.verify(token, TokenType.ACCESS, showtimeId)) {
            is TokenCheck.Valid -> Caller.Member(check.memberId)
            TokenCheck.Expired -> reject(HttpStatus.UNAUTHORIZED, "ACCESS_TOKEN_EXPIRED")
            TokenCheck.Invalid -> reject(HttpStatus.UNAUTHORIZED, "ACCESS_TOKEN_INVALID")
        }
    }

    private fun fromHeader(memberHeader: Long?): Caller =
        if (memberHeader != null) Caller.Member(memberHeader) else reject(HttpStatus.BAD_REQUEST, "MISSING_HEADER")

    private fun reject(status: HttpStatus, code: String) = Caller.Rejected(errors.respond(status, code))

    companion object {
        fun bearer(authorization: String): String? =
            authorization.takeIf { it.startsWith("Bearer ", ignoreCase = true) }?.substring(7)?.trim()?.ifEmpty { null }
    }
}
