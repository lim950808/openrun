package dev.openrun.booking.queue

import dev.openrun.booking.hold.toOffset
import dev.openrun.outbox.OutboxJdbcRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant

sealed interface EnterOutcome {
    data class Entered(val visitorToken: String, val ahead: Long, val enteredAt: Instant, val newlyEntered: Boolean) : EnterOutcome

    /** The member still holds a live access token for this showtime: no new place in line (ADR-0006 §3). */
    data class AlreadyAdmitted(val accessToken: String, val expiresAt: Instant) : EnterOutcome

    /** Before `open_at - entry-lead` (409 QUEUE_NOT_OPEN). */
    data object NotOpen : EnterOutcome
}

sealed interface QueueStatus {
    data class Waiting(val ahead: Long) : QueueStatus

    data class Admitted(val accessToken: String, val expiresAt: Instant) : QueueStatus

    /** Abandoned by the scheduler, or lost by a failed tick (ADR-0006 §2-4). The client re-enters. */
    data object NotInQueue : QueueStatus
}

/** Entry and polling (M2-1, M2-3, M2-4). Polling touches Redis only. */
@Service
class QueueService(
    private val redis: QueueRedis,
    private val codec: TokenCodec,
    private val props: QueueProperties,
    private val jdbc: JdbcTemplate,
    private val tx: TransactionTemplate,
    private val outbox: OutboxJdbcRepository,
) {
    fun enter(showtimeId: Long, memberId: Long, openAt: Instant): EnterOutcome {
        if (Instant.now().isBefore(openAt.minus(props.entryLead))) return EnterOutcome.NotOpen

        val admitted = admittedStatus(showtimeId, memberId)
        if (admitted != null) return EnterOutcome.AlreadyAdmitted(admitted.accessToken, admitted.expiresAt)

        val entry = redis.enqueue(showtimeId, memberId)
        if (entry.newlyAdded) {
            tx.executeWithoutResult {
                val rowId = jdbc.queryForObject(
                    """
                    insert into queue_admission (showtime_id, member_id, entered_at)
                    values (?, ?, ?)
                    returning id
                    """.trimIndent(),
                    Long::class.java,
                    showtimeId,
                    memberId,
                    entry.enteredAt.toOffset(),
                )!!
                outbox.append(QueueEvents.entered(rowId, showtimeId, memberId, entry.enteredAt))
            }
        }
        val ahead = redis.rank(showtimeId, memberId) ?: 0
        return EnterOutcome.Entered(codec.mintVisitor(memberId, showtimeId).token, ahead, entry.enteredAt, entry.newlyAdded)
    }

    fun me(showtimeId: Long, memberId: Long): QueueStatus {
        redis.recordPoll(showtimeId, memberId, Instant.now())
        // Rank first: the scheduler removes a member and writes the admitted key in one script, so a member missing
        // from the ZSET is either admitted already or out of the queue.
        val rank = redis.rank(showtimeId, memberId)
        if (rank != null) return QueueStatus.Waiting(rank)
        return admittedStatus(showtimeId, memberId) ?: QueueStatus.NotInQueue
    }

    private fun admittedStatus(showtimeId: Long, memberId: Long): QueueStatus.Admitted? {
        val token = redis.admittedToken(showtimeId, memberId) ?: return null
        val check = codec.verify(token, TokenType.ACCESS, showtimeId)
        return if (check is TokenCheck.Valid) QueueStatus.Admitted(token, check.expiresAt) else null
    }
}
