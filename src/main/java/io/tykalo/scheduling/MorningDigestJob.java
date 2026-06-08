package io.tykalo.scheduling;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fires the morning digest sweep once an hour, on the hour. Because the digest hour is per-user and
 * resolved in each user's own timezone, the job runs every hour (not only at a fixed UTC window) and
 * {@link MorningDigestService} decides whose local hour is striking. ShedLock guarantees exactly one
 * instance runs the tick on a multi-node deploy; the job itself only supplies the current instant.
 */
@Component
@RequiredArgsConstructor
public class MorningDigestJob {

    private final MorningDigestService morningDigestService;

    @Scheduled(cron = "0 0 * * * *")
    @SchedulerLock(name = "morningDigestJob", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void run() {
        morningDigestService.sendDigests(Instant.now());
    }
}
