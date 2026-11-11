package dev.openrun.api

import java.time.Instant
import java.time.temporal.ChronoUnit

/** Creates a fresh showtime with all 1,500 seats provisioned, so each test owns its inventory. */
fun AdminController.newShowtime(): CreateShowtimeResponse {
    val startsAt = Instant.now().plus(7, ChronoUnit.DAYS)
    return createShowtime(CreateShowtimeRequest(startsAt = startsAt, openAt = startsAt.minus(1, ChronoUnit.DAYS)))
}
