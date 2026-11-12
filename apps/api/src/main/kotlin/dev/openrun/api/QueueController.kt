package dev.openrun.api

import dev.openrun.booking.queue.EnterOutcome
import dev.openrun.booking.queue.QueueService
import dev.openrun.booking.queue.QueueStatus
import dev.openrun.booking.queue.ShowtimeOpenAtJdbc
import dev.openrun.booking.queue.TokenCheck
import dev.openrun.booking.queue.TokenCodec
import dev.openrun.booking.queue.TokenType
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

data class QueueWaitingBody(val visitorToken: String, val ahead: Long, val enteredAt: Instant) {
    val status = "WAITING"
}

data class QueueAdmittedBody(val accessToken: String, val accessTokenExpiresAt: Instant) {
    val status = "ADMITTED"
}

data class QueueRankBody(val ahead: Long) {
    val status = "WAITING"
}

data class QueueOutBody(val status: String = "NOT_IN_QUEUE")

/** Waiting queue API (ADR-0006 §4). */
@RestController
class QueueController(
    private val queue: QueueService,
    private val showtimes: ShowtimeOpenAtJdbc,
    private val codec: TokenCodec,
    private val errors: ApiErrors,
) {
    @PostMapping("/api/showtimes/{showtimeId}/queue")
    fun enter(
        @PathVariable showtimeId: Long,
        @RequestHeader("X-Member-Id") memberId: Long,
    ): ResponseEntity<Any> {
        val openAt = showtimes.openAt(showtimeId) ?: return errors.respond(HttpStatus.NOT_FOUND, "SHOWTIME_NOT_FOUND")
        return when (val outcome = queue.enter(showtimeId, memberId, openAt)) {
            is EnterOutcome.Entered -> ResponseEntity
                .status(if (outcome.newlyEntered) HttpStatus.CREATED else HttpStatus.OK)
                .body(QueueWaitingBody(outcome.visitorToken, outcome.ahead, outcome.enteredAt))
            is EnterOutcome.AlreadyAdmitted -> ResponseEntity.ok(QueueAdmittedBody(outcome.accessToken, outcome.expiresAt))
            EnterOutcome.NotOpen -> errors.respond(HttpStatus.CONFLICT, "QUEUE_NOT_OPEN")
        }
    }

    @GetMapping("/api/showtimes/{showtimeId}/queue/me")
    fun me(
        @PathVariable showtimeId: Long,
        @RequestHeader("Authorization", required = false) authorization: String?,
    ): ResponseEntity<Any> {
        authorization ?: return errors.respond(HttpStatus.UNAUTHORIZED, "VISITOR_TOKEN_REQUIRED")
        val token = CallerIdentity.bearer(authorization) ?: return errors.respond(HttpStatus.UNAUTHORIZED, "VISITOR_TOKEN_INVALID")
        val memberId = when (val check = codec.verify(token, TokenType.VISITOR, showtimeId)) {
            is TokenCheck.Valid -> check.memberId
            TokenCheck.Expired -> return errors.respond(HttpStatus.UNAUTHORIZED, "VISITOR_TOKEN_EXPIRED")
            TokenCheck.Invalid -> return errors.respond(HttpStatus.UNAUTHORIZED, "VISITOR_TOKEN_INVALID")
        }
        return when (val status = queue.me(showtimeId, memberId)) {
            is QueueStatus.Waiting -> ResponseEntity.ok(QueueRankBody(status.ahead))
            is QueueStatus.Admitted -> ResponseEntity.ok(QueueAdmittedBody(status.accessToken, status.expiresAt))
            QueueStatus.NotInQueue -> ResponseEntity.ok(QueueOutBody())
        }
    }
}
