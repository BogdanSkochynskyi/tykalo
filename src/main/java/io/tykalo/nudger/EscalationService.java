package io.tykalo.nudger;

import io.tykalo.list.Task;
import io.tykalo.list.TaskRepository;
import io.tykalo.telegram.TelegramMessageGateway;
import io.tykalo.user.User;
import io.tykalo.user.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Escalates overdue Project tasks to their owners' nudgers (TK-156). Driven every 30 minutes by
 * {@code EscalationJob}: for the sweep instant it walks every overdue {@code PROJECT} task and, per
 * task, determines the highest escalation rung whose {@code delay_minutes} past {@code due_at} has
 * elapsed, then delivers that rung to each recipient nudger who has not already received it. The
 * recipient set honours the task's per-task assignment (TK-158): a private task is skipped, an
 * assigned task narrows to its pinned nudgers (still intersected with the {@link NudgerStatus#ACTIVE}
 * set), and an unassigned task falls back to the owner's whole active set. Splitting this out of the
 * job keeps it unit-testable without Quartz/ShedLock.
 *
 * <p>"Current level" rule: only the single most-advanced elapsed rung is considered, so a nudger
 * added later — or the first sweep after downtime — receives the current level rather than a burst of
 * every past level. Dedup is per {@code (task, nudger, level)} via {@code nudge_log}; a level is never
 * re-sent to the same nudger (the {@code uq_nudge_log_target_nudger_level} constraint backs this even
 * against a ShedLock-lease race). Each send is isolated: a failure to one nudger is logged and the
 * sweep continues.
 *
 * <p>Anti-fatigue throttling (TK-159) is not applied here — that ticket depends on this one and adds
 * the per-nudger daily cap around the per-nudger loop below. Escalation recipients are nudgers, not
 * the owner, so the owner's quiet hours intentionally do not gate it.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EscalationService {

    private final TaskRepository taskRepository;
    private final UserRepository userRepository;
    private final NudgerRepository nudgerRepository;
    private final EscalationPolicyRepository escalationPolicyRepository;
    private final NudgeLogRepository nudgeLogRepository;
    private final TaskNudgerService taskNudgerService;
    private final EscalationRenderer renderer;
    private final TelegramMessageGateway gateway;

    private record SentKey(UUID targetId, UUID nudgerId, int level) {
    }

    /** Delivers the due escalation level to the active nudgers of every overdue Project task. */
    @Transactional
    public void runEscalations(final Instant now) {
        final List<Task> overdue = taskRepository.findOverdueProjectTasks(now);
        if (overdue.isEmpty()) {
            return;
        }
        final List<UUID> taskIds = overdue.stream().map(Task::getId).toList();
        final Map<UUID, User> owners = usersById(overdue.stream().map(Task::getOwnerId).toList());
        final Map<UUID, List<Nudger>> activeNudgers = activeNudgersByOwner(owners.keySet());
        final Map<UUID, User> nudgerUsers = usersById(activeNudgers.values().stream()
                .flatMap(List::stream).map(Nudger::getNudgerUserId).toList());
        final Map<UUID, List<EscalationPolicy>> ladders = laddersByTask(taskIds);
        final Map<UUID, Set<UUID>> assignments = taskNudgerService.assignmentsByTask(taskIds);
        final Set<SentKey> sent = sentKeys(taskIds);

        int delivered = 0;
        for (final Task task : overdue) {
            delivered += escalateTask(task, owners, activeNudgers, nudgerUsers, ladders, assignments, sent, now);
        }
        log.info("Escalation sweep at {} over {} overdue task(s) sent {} nudge(s)", now, overdue.size(), delivered);
    }

    private int escalateTask(final Task task, final Map<UUID, User> owners,
                             final Map<UUID, List<Nudger>> activeNudgers, final Map<UUID, User> nudgerUsers,
                             final Map<UUID, List<EscalationPolicy>> ladders,
                             final Map<UUID, Set<UUID>> assignments, final Set<SentKey> sent,
                             final Instant now) {
        final User owner = owners.get(task.getOwnerId());
        if (owner == null) {
            log.warn("Skipping escalation for task id={} — owner id={} not found", task.getId(), task.getOwnerId());
            return 0;
        }
        if (task.isNudgersPrivate()) {
            return 0;
        }
        final List<Nudger> nudgers = recipientsFor(task, activeNudgers.getOrDefault(owner.getId(), List.of()),
                assignments.get(task.getId()));
        if (nudgers.isEmpty()) {
            return 0;
        }
        final Optional<EscalationPolicy> level = currentLevel(
                ladders.getOrDefault(task.getId(), List.of()), task.getDueAt().orElseThrow(), now);
        if (level.isEmpty()) {
            return 0;
        }
        int delivered = 0;
        for (final Nudger nudger : nudgers) {
            if (escalateToNudger(owner, task, nudger, level.get(), nudgerUsers, sent, now)) {
                delivered++;
            }
        }
        return delivered;
    }

    private boolean escalateToNudger(final User owner, final Task task, final Nudger nudger,
                                     final EscalationPolicy policy, final Map<UUID, User> nudgerUsers,
                                     final Set<SentKey> sent, final Instant now) {
        final SentKey key = new SentKey(task.getId(), nudger.getId(), policy.getLevel());
        if (sent.contains(key)) {
            return false;
        }
        final User nudgerUser = nudgerUsers.get(nudger.getNudgerUserId());
        if (nudgerUser == null) {
            log.warn("Skipping escalation to nudger id={} — its user id={} not found",
                    nudger.getId(), nudger.getNudgerUserId());
            return false;
        }
        try {
            final String body = renderer.render(owner, task, policy.getRevealFields(), now);
            final NudgeLog entry = NudgeLog.of(EscalationTargetType.TASK, task.getId(), nudger.getId(),
                    policy.getLevel(), now, body);
            gateway.sendMarkdown(nudgerUser.getTgChatId(), body, renderer.ackKeyboard(entry.getId()));
            nudgeLogRepository.save(entry);
            sent.add(key);
            log.info("Escalated level={} task id={} to nudger id={} (user id={})",
                    policy.getLevel(), task.getId(), nudger.getId(), nudgerUser.getId());
            return true;
        } catch (final RuntimeException e) {
            log.warn("Failed to escalate task id={} to nudger id={}", task.getId(), nudger.getId(), e);
            return false;
        }
    }

    /**
     * The Nudgers an overdue task escalates to: its per-task assignment intersected with the owner's
     * active set (TK-158), or the whole active set when the task has no assignment (the default). A
     * Nudger pinned to the task but no longer active drops out, since {@code active} is already the
     * ACTIVE-only list.
     */
    private static List<Nudger> recipientsFor(final Task task, final List<Nudger> active,
                                              final @Nullable Set<UUID> assignedNudgerIds) {
        if (assignedNudgerIds == null || assignedNudgerIds.isEmpty()) {
            return active;
        }
        return active.stream().filter(nudger -> assignedNudgerIds.contains(nudger.getId())).toList();
    }

    /**
     * The highest ladder rung whose {@code delay_minutes} past {@code dueAt} has elapsed by {@code now},
     * or empty when not even the first rung is due. {@code ladder} is ascending by level, so the last
     * elapsed rung is the most advanced.
     */
    private static Optional<EscalationPolicy> currentLevel(final List<EscalationPolicy> ladder,
                                                           final Instant dueAt, final Instant now) {
        EscalationPolicy current = null;
        for (final EscalationPolicy rung : ladder) {
            if (!now.isBefore(dueAt.plus(Duration.ofMinutes(rung.getDelayMinutes())))) {
                current = rung;
            }
        }
        return Optional.ofNullable(current);
    }

    private Map<UUID, User> usersById(final List<UUID> ids) {
        return userRepository.findAllById(ids.stream().distinct().toList()).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
    }

    private Map<UUID, List<Nudger>> activeNudgersByOwner(final Collection<UUID> ownerIds) {
        if (ownerIds.isEmpty()) {
            return Map.of();
        }
        return nudgerRepository.findByOwnerIdInAndStatus(ownerIds, NudgerStatus.ACTIVE).stream()
                .collect(Collectors.groupingBy(Nudger::getOwnerId));
    }

    private Map<UUID, List<EscalationPolicy>> laddersByTask(final List<UUID> taskIds) {
        final Map<UUID, List<EscalationPolicy>> byTask = new LinkedHashMap<>();
        for (final EscalationPolicy policy : escalationPolicyRepository
                .findByTargetTypeAndTargetIdInOrderByLevelAsc(EscalationTargetType.TASK, taskIds)) {
            byTask.computeIfAbsent(policy.getTargetId(), k -> new ArrayList<>()).add(policy);
        }
        return byTask;
    }

    private Set<SentKey> sentKeys(final List<UUID> taskIds) {
        return nudgeLogRepository.findByTargetTypeAndTargetIdIn(EscalationTargetType.TASK, taskIds).stream()
                .map(entry -> new SentKey(entry.getTargetId(), entry.getNudgerId(), entry.getLevel()))
                .collect(Collectors.toCollection(HashSet::new));
    }
}
