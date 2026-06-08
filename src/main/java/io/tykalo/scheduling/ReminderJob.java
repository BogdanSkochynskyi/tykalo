package io.tykalo.scheduling;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fires the overdue-reminder sweep every 15 minutes. {@link ReminderService} decides, per overdue
 * task, whether a new reminder tier (+2h/+6h/+12h past due) is owed now. ShedLock guarantees exactly
 * one instance runs the tick on a multi-node deploy; the job itself only supplies the current instant.
 */
@Component
@RequiredArgsConstructor
public class ReminderJob {

    private final ReminderService reminderService;

    @Scheduled(cron = "0 */15 * * * *")
    @SchedulerLock(name = "reminderJob", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void run() {
        reminderService.sendDueReminders(Instant.now());
    }
}
