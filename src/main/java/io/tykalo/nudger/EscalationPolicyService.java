package io.tykalo.nudger;

import io.tykalo.list.ListType;
import io.tykalo.list.Task;
import io.tykalo.list.TaskCreatedEvent;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds the default escalation ladder for Project tasks (TK-155). Every task created in a
 * {@link ListType#PROJECT} list gets a three-rung ladder — number after 2h, title after 6h, full
 * description after 12h — so the escalation cron (TK-156) has rungs to walk. The ladder is created
 * eagerly on task creation regardless of due date or assigned nudgers; the cron checks those at fire
 * time.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EscalationPolicyService {

    static final int LEVEL_1_DELAY_MINUTES = 120;
    static final int LEVEL_2_DELAY_MINUTES = 360;
    static final int LEVEL_3_DELAY_MINUTES = 720;

    private final EscalationPolicyRepository escalationPolicyRepository;

    /**
     * Reacts to a task being created: for a {@link ListType#PROJECT} list it seeds the default
     * ladder. Runs synchronously inside the publisher's transaction, so the ladder is persisted
     * atomically with the task; non-Project lists are ignored.
     */
    @EventListener
    public void onTaskCreated(final TaskCreatedEvent event) {
        if (event.listType() == ListType.PROJECT) {
            createDefaults(event.task());
        }
    }

    /**
     * Persists the default three-rung ladder for {@code task} ({@code target_type=TASK},
     * {@code target_id=task.id}). Idempotent: if a ladder already exists for the task — a replayed
     * event or a second call — nothing is created and an empty list is returned.
     */
    @Transactional
    public List<EscalationPolicy> createDefaults(final Task task) {
        final UUID taskId = Objects.requireNonNull(task.getId(), "task must be persisted before creating policies");
        final boolean alreadySeeded = !escalationPolicyRepository
                .findByTargetTypeAndTargetIdOrderByLevelAsc(EscalationTargetType.TASK, taskId)
                .isEmpty();
        if (alreadySeeded) {
            log.debug("Default escalation ladder already exists for task {} — skipping", taskId);
            return List.of();
        }
        final List<EscalationPolicy> ladder = List.of(
                EscalationPolicy.of(EscalationTargetType.TASK, taskId, 1, LEVEL_1_DELAY_MINUTES, RevealField.NUMBER),
                EscalationPolicy.of(EscalationTargetType.TASK, taskId, 2, LEVEL_2_DELAY_MINUTES, RevealField.TITLE),
                EscalationPolicy.of(EscalationTargetType.TASK, taskId, 3, LEVEL_3_DELAY_MINUTES, RevealField.DESCRIPTION));
        final List<EscalationPolicy> saved = escalationPolicyRepository.saveAll(ladder);
        log.info("Seeded default escalation ladder ({} levels) for task {}", saved.size(), taskId);
        return saved;
    }
}
