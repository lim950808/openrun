package dev.openrun.api

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** Proves the ShedLock-guarded scheduler wiring. Real jobs (janitor, queue admission) arrive in M2/M3. */
@Component
class HeartbeatJob {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${openrun.heartbeat.delay:60000}")
    @SchedulerLock(name = "heartbeat", lockAtLeastFor = "1s", lockAtMostFor = "1m")
    fun beat() {
        log.info("heartbeat")
    }
}
