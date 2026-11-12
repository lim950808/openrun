package dev.openrun.api

import dev.openrun.booking.hold.HoldCommand
import dev.openrun.booking.hold.HoldError
import dev.openrun.booking.hold.HoldOutcome
import dev.openrun.booking.hold.HoldService
import dev.openrun.booking.hold.LockStrategy
import dev.openrun.booking.hold.ReleaseOutcome
import dev.openrun.booking.hold.ReleaseService
import dev.openrun.booking.hold.SeatStatusQuery
import dev.openrun.booking.queue.QueueProperties
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

data class HoldRequest(val seatIds: List<Long> = emptyList())

/**
 * Seat API. Seat lookup and hold need a Bearer access token for the showtime (ADR-0006 §3), or `X-Member-Id` when
 * `openrun.queue.require-access-token=false` (M1 benchmark). Cancel accepts either. See [CallerIdentity].
 * `X-Hold-Strategy` is honored only when `openrun.hold.allow-strategy-override=true` (benchmarks).
 */
@RestController
class HoldController(
    private val holds: HoldService,
    private val releases: ReleaseService,
    private val seatStatus: SeatStatusQuery,
    private val callers: CallerIdentity,
    private val props: QueueProperties,
    private val errors: ApiErrors,
) {
    @PostMapping("/api/showtimes/{showtimeId}/holds")
    fun hold(
        @PathVariable showtimeId: Long,
        @RequestHeader("Authorization", required = false) authorization: String?,
        @RequestHeader("X-Member-Id", required = false) memberHeader: Long?,
        @RequestHeader("X-Hold-Strategy", required = false) strategyHeader: String?,
        @RequestBody body: HoldRequest,
    ): ResponseEntity<Any> {
        val memberId = when (val caller = callers.forSeatApi(showtimeId, authorization, memberHeader)) {
            is Caller.Member -> caller.memberId
            is Caller.Rejected -> return caller.response
        }
        val requested = strategyHeader?.let { h -> LockStrategy.entries.firstOrNull { it.name == h } }
        if (strategyHeader != null && requested == null) return errors.respond(HttpStatus.BAD_REQUEST, "INVALID_STRATEGY")

        return when (val outcome = holds.hold(HoldCommand(showtimeId, memberId, body.seatIds, requested))) {
            is HoldOutcome.Success -> ResponseEntity.status(HttpStatus.CREATED).body(outcome.result)
            is HoldOutcome.Failure -> when (outcome.error) {
                HoldError.InvalidSeatCount -> errors.respond(HttpStatus.BAD_REQUEST, outcome.error.code)
                HoldError.ActiveReservationExists, HoldError.SeatTaken -> errors.respond(HttpStatus.CONFLICT, outcome.error.code)
            }
        }
    }

    @DeleteMapping("/api/reservations/{reservationId}")
    fun release(
        @PathVariable reservationId: Long,
        @RequestHeader("Authorization", required = false) authorization: String?,
        @RequestHeader("X-Member-Id", required = false) memberHeader: Long?,
    ): ResponseEntity<Any> {
        val memberId = when (val caller = callers.forReservation(authorization, memberHeader)) {
            is Caller.Member -> caller.memberId
            is Caller.Rejected -> return caller.response
        }
        return when (releases.release(reservationId, memberId)) {
            ReleaseOutcome.Released -> ResponseEntity.noContent().build()
            ReleaseOutcome.IllegalTransition -> errors.respond(HttpStatus.CONFLICT, "ILLEGAL_TRANSITION")
        }
    }

    /** The member id is not used here; the token only gates access. */
    @GetMapping("/api/showtimes/{showtimeId}/seats")
    fun seats(
        @PathVariable showtimeId: Long,
        @RequestHeader("Authorization", required = false) authorization: String?,
    ): ResponseEntity<Any> {
        if (props.requireAccessToken) {
            val caller = callers.forSeatApi(showtimeId, authorization, null)
            if (caller is Caller.Rejected) return caller.response
        }
        return ResponseEntity.ok(seatStatus.seats(showtimeId))
    }
}
