package dev.openrun.booking.hold

import dev.openrun.outbox.OutboxJdbcRepository
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.interceptor.TransactionAspectSupport
import java.time.Instant
import java.time.OffsetDateTime

data class HoldCommand(
    val showtimeId: Long,
    val memberId: Long,
    val seatIds: List<Long>,
    /** Honored only when `openrun.hold.allow-strategy-override=true`. */
    val strategy: LockStrategy? = null,
)

data class SeatPrice(val seatId: Long, val price: Int)

data class HoldResult(
    val reservationId: Long,
    val expiresAt: Instant,
    val seats: List<SeatPrice>,
    val totalAmount: Int,
    val strategy: LockStrategy,
)

enum class HoldError(val code: String) {
    InvalidSeatCount("INVALID_SEAT_COUNT"),
    ActiveReservationExists("ACTIVE_RESERVATION_EXISTS"),
    SeatTaken("SEAT_TAKEN"),
}

sealed interface HoldOutcome {
    data class Success(val result: HoldResult) : HoldOutcome

    data class Failure(val error: HoldError) : HoldOutcome
}

/** none → HOLDING (ADR-0004 §2). Admission-token guard arrives with M2. */
@Service
class HoldService(
    private val jdbc: JdbcTemplate,
    private val outbox: OutboxJdbcRepository,
    private val props: HoldProperties,
    strategies: List<SeatClaimStrategy>,
) {
    private val strategies: Map<LockStrategy, SeatClaimStrategy> = strategies.associateBy { it.name }

    fun strategyFor(requested: LockStrategy?): LockStrategy =
        if (requested != null && props.allowStrategyOverride) requested else props.strategy

    @Transactional
    fun hold(cmd: HoldCommand): HoldOutcome {
        val seatIds = cmd.seatIds.sorted()
        if (seatIds.size !in 1..MAX_SEATS || seatIds.distinct().size != seatIds.size) {
            return HoldOutcome.Failure(HoldError.InvalidSeatCount)
        }
        val strategy = strategyFor(cmd.strategy)

        // expires_at comes from the database clock, the same clock every free-check and the janitor use.
        val (reservationId, expiresAt) = try {
            jdbc.queryForObject(
                """
                insert into reservation (showtime_id, member_id, status, expires_at, strategy)
                values (?, ?, ?, now() + ? * interval '1 millisecond', ?)
                returning id, expires_at
                """.trimIndent(),
                { rs, _ -> rs.getLong("id") to rs.getObject("expires_at", OffsetDateTime::class.java).toInstant() },
                cmd.showtimeId,
                cmd.memberId,
                ReservationStatus.HOLDING.name,
                props.ttl.toMillis(),
                strategy.name,
            )!!
        } catch (e: DuplicateKeyException) {
            return rollback(HoldError.ActiveReservationExists)
        }

        val claim = strategies.getValue(strategy).claim(cmd.showtimeId, seatIds, reservationId, cmd.memberId, expiresAt)
        if (claim !is ClaimResult.Claimed) return rollback(HoldError.SeatTaken)

        val seats = seatIds.map { SeatPrice(it, claim.prices.getValue(it)) }
        jdbc.batchUpdate(
            "insert into reservation_seat (reservation_id, seat_id, price) values (?, ?, ?)",
            seats.map { arrayOf<Any>(reservationId, it.seatId, it.price) },
        )
        val total = seats.sumOf { it.price }
        // held_at is taken after the claim returned, i.e. after any previous holder's release was committed for the
        // safe strategies. created_at (transaction start) can precede that and would fake an overlap.
        jdbc.update("update reservation set total_amount = ?, held_at = clock_timestamp() where id = ?", total, reservationId)

        val result = HoldResult(reservationId, expiresAt, seats, total, strategy)
        outbox.append(ReservationEvents.held(result, cmd.showtimeId, cmd.memberId, strategy))
        return HoldOutcome.Success(result)
    }

    /** Undo the reservation insert and any partial claim without using an exception for flow control. */
    private fun rollback(error: HoldError): HoldOutcome {
        TransactionAspectSupport.currentTransactionStatus().setRollbackOnly()
        return HoldOutcome.Failure(error)
    }

    companion object {
        const val MAX_SEATS = 2
    }
}
