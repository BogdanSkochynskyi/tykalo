package io.tykalo.scheduling;

import io.tykalo.nudger.EscalationService;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fires the nudger-escalation sweep every 30 minutes (TK-156). {@link EscalationService} decides, per
 * overdue Project task, which escalation level is due now and delivers it to the owner's active
 * nudgers. ShedLock guarantees exactly one instance runs the tick on a multi-node deploy; the job
 * itself only supplies the current instant.
 */
@Component
@RequiredArgsConstructor
public class EscalationJob {

    private final EscalationService escalationService;

    @Scheduled(cron = "0 */30 * * * *")
    @SchedulerLock(name = "escalationJob", lockAtMostFor = "PT20M", lockAtLeastFor = "PT1M")
    public void run() {
        escalationService.runEscalations(Instant.now());
    }
}
