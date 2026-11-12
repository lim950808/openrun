package dev.openrun.api

import dev.openrun.booking.queue.TokenCodec

/**
 * `Authorization` header value with an access token for [memberId] on [showtimeId], minted directly with the app's
 * codec. Seat API tests use it instead of walking the queue; queue tests get tokens through the scheduler.
 */
fun TokenCodec.bearerFor(showtimeId: Long, memberId: Long): String = "Bearer ${mintAccess(memberId, showtimeId).token}"
