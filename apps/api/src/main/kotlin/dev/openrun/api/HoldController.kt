package dev.openrun.api

import dev.openrun.booking.hold.HoldCommand
import dev.openrun.booking.hold.HoldError
import dev.openrun.booking.hold.HoldOutcome
import dev.openrun.booking.hold.HoldService
import dev.openrun.booking.hold.LockStrategy
import dev.openrun.booking.hold.ReleaseOutcome
import dev.openrun.booking.hold.ReleaseService
import dev.openrun.booking.hold.SeatStatusQuery
import dev.openrun.booking.hold.SeatView
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
 * M1 seat API. `X-Member-Id` is the interim identity until M2 replaces it with the access token.
 * `X-Hold-Strategy` is honored only when `openrun.hold.allow-strategy-override=true` (benchmarks).
 */
@RestController
class HoldController(
    private val holds: HoldService,
    private val releases: ReleaseService,
    private val seatStatus: SeatStatusQuery,
    private val errors: ApiErrors,
) {
    @PostMapping("/api/showtimes/{showtimeId}/holds")
    fun hold(
        @PathVariable showtimeId: Long,
        @RequestHeader("X-Member-Id") memberId: Long,
        @RequestHeader("X-Hold-Strategy", required = false) strategyHeader: String?,
        @RequestBody body: HoldRequest,
    ): ResponseEntity<Any> {
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
        @RequestHeader("X-Member-Id") memberId: Long,
    ): ResponseEntity<Any> =
        when (releases.release(reservationId, memberId)) {
            ReleaseOutcome.Released -> ResponseEntity.noContent().build()
            ReleaseOutcome.IllegalTransition -> errors.respond(HttpStatus.CONFLICT, "ILLEGAL_TRANSITION")
        }

    @GetMapping("/api/showtimes/{showtimeId}/seats")
    fun seats(@PathVariable showtimeId: Long): List<SeatView> = seatStatus.seats(showtimeId)
}
