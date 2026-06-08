package io.tykalo.scheduling;

import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Reference implementation of the {@code @Scheduled} + {@code @SchedulerLock} pattern, doubling as a
 * lightweight liveness signal. On a multi-instance deploy exactly one node logs the heartbeat per
 * tick — the others find the lock held and skip. The real scheduling jobs (morning digest, reminders,
 * escalation) copy this shape: a cron expression plus a uniquely named {@code @SchedulerLock}.
 */
@Component
@Slf4j
public class HeartbeatJob {

    @Scheduled(cron = "0 */30 * * * *")
    @SchedulerLock(name = "heartbeatJob", lockAtMostFor = "PT5M", lockAtLeastFor = "PT1M")
    public void heartbeat() {
        log.debug("Scheduler heartbeat — this instance holds the distributed lock for this tick");
    }
}
