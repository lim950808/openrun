package dev.openrun.api

import dev.openrun.catalog.ShowtimeRepository
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/** Demo server settings (ADR-0009 §4). */
@ConfigurationProperties("openrun.demo")
data class DemoProperties(
    /** Interval between demo showtimes, counted from the top of the hour (UTC). Zero or negative = off. */
    val showtimeEvery: Duration = Duration.ZERO,
    /** How often the scheduler checks; also its initial delay, so a fresh start finishes seeding first. */
    val tickMs: Long = 60_000,
)

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DemoProperties::class)
class DemoConfig

/**
 * Opens a showtime at every interval boundary so a demo visitor always finds one to queue for (ADR-0009 §4).
 *
 * Each tick computes the next boundary strictly after `now`: a multiple of `openrun.demo.showtime-every` counted
 * from the top of the UTC hour (PT15M → :00, :15, :30, :45; KST is UTC+9 whole hours, so the same wall-clock
 * minutes). If no showtime has that `open_at` yet, one is created with `startsAt = openAt + 7 days` through
 * [ShowtimeProvisioner] (JPA showtime → JDBC `seat_hold` rows, ADR-0003). Same `open_at` → nothing (idempotent).
 * One instance runs at a time (ShedLock `demo-showtime`). Off when the interval is zero, the default.
 */
@Component
class DemoShowtimeScheduler(
    private val props: DemoProperties,
    private val showtimes: ShowtimeRepository,
    private val provisioner: ShowtimeProvisioner,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${openrun.demo.tick-ms:60000}", initialDelayString = "\${openrun.demo.tick-ms:60000}")
    @SchedulerLock(name = "demo-showtime", lockAtMostFor = "5m", lockAtLeastFor = "10s")
    fun scheduledTick() {
        tick()
    }

    /** Returns the id of the showtime created this tick, or null when off or the boundary already has one. */
    fun tick(now: Instant = Instant.now()): Long? {
        val every = props.showtimeEvery
        if (every.isZero || every.isNegative) return null

        val openAt = nextBoundary(now, every)
        if (showtimes.existsByOpenAt(openAt)) return null

        val created = provisioner.create(startsAt = openAt.plus(7, ChronoUnit.DAYS), openAt = openAt)
        log.info("demo_showtime_created showtimeId={} openAt={} startsAt={}", created.showtimeId, openAt, openAt.plus(7, ChronoUnit.DAYS))
        return created.showtimeId
    }

    companion object {
        /** Smallest instant strictly after [now] that is a whole number of [every] past the top of the UTC hour. */
        fun nextBoundary(now: Instant, every: Duration): Instant {
            val hour = now.truncatedTo(ChronoUnit.HOURS)
            val elapsed = Duration.between(hour, now)
            val steps = elapsed.toNanos() / every.toNanos() + 1
            return hour.plus(every.multipliedBy(steps))
        }
    }
}
