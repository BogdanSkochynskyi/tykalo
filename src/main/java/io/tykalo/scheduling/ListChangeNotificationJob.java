package io.tykalo.scheduling;

import io.tykalo.notification.ListChangeAggregator;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives the shared-list notification flushes (TK-196). The windowed sweep runs every 20 seconds and
 * sends INSTANT (30s) and BATCHED (10-min) buckets whose window has closed; the daily sweep runs hourly
 * and sends each DAILY_DIGEST user their summary when their local morning hour strikes — both delegate
 * to {@link ListChangeAggregator}, which decides what is due. ShedLock guarantees exactly one instance
 * runs each tick on a multi-node deploy; the job only supplies the current instant.
 */
@Component
@RequiredArgsConstructor
public class ListChangeNotificationJob {

    private final ListChangeAggregator aggregator;

    @Scheduled(cron = "*/20 * * * * *")
    @SchedulerLock(name = "listChangeWindowFlush", lockAtMostFor = "PT1M", lockAtLeastFor = "PT5S")
    public void flushWindows() {
        aggregator.flushDueWindows(Instant.now());
    }

    @Scheduled(cron = "0 0 * * * *")
    @SchedulerLock(name = "listChangeDailyDigest", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void flushDailyDigests() {
        aggregator.flushDailyDigests(Instant.now());
    }
}
