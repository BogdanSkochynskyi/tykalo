package io.tykalo.nudger;

import io.tykalo.list.Task;
import io.tykalo.list.TaskRepository;
import io.tykalo.user.User;
import io.tykalo.user.UserRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages which Nudgers a single task escalates to (TK-158). A task can be in one of three states,
 * which together drive {@link EscalationService}:
 *
 * <ul>
 *   <li><b>default</b> — no {@code task_nudgers} rows and {@code nudgers_private = false}: escalation
 *       falls back to the owner's full active set (the TK-156 behaviour);</li>
 *   <li><b>assigned</b> — one or more rows: escalation is narrowed to exactly those Nudgers (still
 *       intersected with the active set at fire time);</li>
 *   <li><b>private</b> — {@code nudgers_private = true}: escalation skips the task entirely.</li>
 * </ul>
 *
 * <p>Username resolution is delegated to {@link NudgerService#find}, so "not one of your Nudgers" and
 * "not on Tykalo" are reported identically to the {@code /nudgers} commands.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TaskNudgerService {

    private final TaskNudgerRepository taskNudgerRepository;
    private final NudgerRepository nudgerRepository;
    private final NudgerService nudgerService;
    private final TaskRepository taskRepository;
    private final UserRepository userRepository;

    /**
     * Outcome of {@code /task <id> nudgers @a @b}: {@code assigned} are the handles now pinned to the
     * task, {@code notNudgers} exist but are not the owner's Nudgers, {@code notRegistered} have no
     * account. {@code applied} is false when nothing resolved, in which case the existing assignment
     * was left untouched rather than wiped.
     */
    public record AssignResult(List<String> assigned, List<String> notNudgers,
                               List<String> notRegistered, boolean applied) {
    }

    /**
     * Replaces a task's assignment with the Nudgers behind {@code usernames} and clears the private
     * flag. Replace (not merge) semantics: re-running with a different set overwrites the previous one.
     * If none of the handles resolve to one of the owner's Nudgers the assignment is left as-is.
     */
    @Transactional
    public AssignResult assign(final User owner, final UUID taskId, final List<String> usernames) {
        final List<Nudger> resolved = new ArrayList<>();
        final Set<String> assigned = new LinkedHashSet<>();
        final List<String> notNudgers = new ArrayList<>();
        final List<String> notRegistered = new ArrayList<>();
        for (final String username : usernames) {
            switch (nudgerService.find(owner, username)) {
                case NudgerActionResult.Ok ok -> {
                    resolved.add(ok.nudger());
                    assigned.add(ok.invitee().getTgUsername());
                }
                case NudgerActionResult.NotANudger notANudger -> notNudgers.add(notANudger.username());
                case NudgerActionResult.NotRegistered notReg -> notRegistered.add(notReg.username());
                case NudgerActionResult.Unchanged ignored -> { }
            }
        }
        if (resolved.isEmpty()) {
            return new AssignResult(List.of(), notNudgers, notRegistered, false);
        }
        taskNudgerRepository.deleteByTaskId(taskId);
        require(taskId).setNudgersPrivate(false);
        resolved.stream().distinct().forEach(n -> taskNudgerRepository.save(TaskNudger.of(taskId, n.getId())));
        log.info("Assigned {} nudger(s) to task {}", resolved.size(), taskId);
        return new AssignResult(new ArrayList<>(assigned), notNudgers, notRegistered, true);
    }

    /**
     * Adds a single Nudger to a task's assignment and clears the private flag (the inline picker's
     * per-Nudger button). Idempotent: a replayed tap that already linked the pair is a no-op.
     */
    @Transactional
    public void assignNudger(final UUID taskId, final UUID nudgerId) {
        require(taskId).setNudgersPrivate(false);
        if (!taskNudgerRepository.existsByTaskIdAndNudgerId(taskId, nudgerId)) {
            taskNudgerRepository.save(TaskNudger.of(taskId, nudgerId));
            log.info("Added nudger {} to task {}", nudgerId, taskId);
        }
    }

    /** Makes a task private: clears any assignment and flags it so escalation skips it. */
    @Transactional
    public void makePrivate(final UUID taskId) {
        taskNudgerRepository.deleteByTaskId(taskId);
        require(taskId).setNudgersPrivate(true);
        log.info("Marked task {} private (no escalation)", taskId);
    }

    /** Reverts a task to the default: clears any assignment and the private flag (use all active Nudgers). */
    @Transactional
    public void useDefault(final UUID taskId) {
        taskNudgerRepository.deleteByTaskId(taskId);
        require(taskId).setNudgersPrivate(false);
        log.info("Reset task {} to default nudger set", taskId);
    }

    /** The usernames currently pinned to a task, for display; empty when the task uses the default set. */
    @Transactional(readOnly = true)
    public List<String> assignedUsernames(final UUID taskId) {
        final List<UUID> nudgerIds = taskNudgerRepository.findByTaskId(taskId).stream()
                .map(TaskNudger::getNudgerId).toList();
        if (nudgerIds.isEmpty()) {
            return List.of();
        }
        final List<UUID> userIds = nudgerRepository.findAllById(nudgerIds).stream()
                .map(Nudger::getNudgerUserId).toList();
        return userRepository.findAllById(userIds).stream()
                .map(User::getTgUsername)
                .sorted()
                .toList();
    }

    /**
     * The Nudger ids pinned to each of {@code taskIds}, for the escalation cron's batch filter. Tasks
     * with no assignment are absent from the map (escalation treats them as "use the default set").
     */
    @Transactional(readOnly = true)
    public Map<UUID, Set<UUID>> assignmentsByTask(final Collection<UUID> taskIds) {
        if (taskIds.isEmpty()) {
            return Map.of();
        }
        return taskNudgerRepository.findByTaskIdIn(taskIds).stream()
                .collect(Collectors.groupingBy(TaskNudger::getTaskId,
                        Collectors.mapping(TaskNudger::getNudgerId, Collectors.toCollection(HashSet::new))));
    }

    private Task require(final UUID taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
    }
}
