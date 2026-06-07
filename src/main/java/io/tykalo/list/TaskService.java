package io.tykalo.list;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CRUD and lifecycle operations over {@link Task}. Deletion is soft (an {@code archivedAt} stamp).
 * Time is stored in UTC; {@link #findToday} takes the caller's zone to map "today" onto an instant
 * window, since the user's wall-clock day depends on their timezone.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TaskService {

    private final TaskRepository taskRepository;
    private final ListRepository listRepository;

    @Transactional
    public Task createTask(final UUID listId, final String title) {
        return createTask(listId, title, null, null);
    }

    /** Creates a task with an optional deadline ({@code dueAt} is stored in UTC; {@code null} = no due date). */
    @Transactional
    public Task createTask(final UUID listId, final String title, final @Nullable Instant dueAt) {
        return createTask(listId, title, dueAt, null);
    }

    /**
     * Creates a task with an optional deadline and recurrence rule. {@code dueAt} is stored in UTC;
     * {@code recurrenceRule} is the short {@code FREQ=…} fragment from {@link RecurrenceParser}
     * (the full RRULE grammar lands in TK-201). Either may be {@code null}.
     */
    @Transactional
    public Task createTask(final UUID listId, final String title, final @Nullable Instant dueAt,
                           final @Nullable String recurrenceRule) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Task title must not be blank");
        }
        final TaskList list = listRepository.findById(listId)
                .orElseThrow(() -> new IllegalArgumentException("List not found: " + listId));
        final Task task = Task.create(list, title.strip());
        task.setDueAt(dueAt);
        task.setRecurrenceRule(recurrenceRule);
        final Task saved = taskRepository.save(task);
        log.info("Created task id={} list={} owner={} dueAt={} recurrence={}",
                saved.getId(), list.getId(), saved.getOwnerId(), dueAt, recurrenceRule);
        return saved;
    }

    /**
     * Creates one task per title in a single transaction (so the batch is all-or-nothing). Blank
     * titles are skipped and remaining titles are stripped; the returned list reflects what was
     * actually persisted.
     */
    @Transactional
    public List<Task> createTasks(final UUID listId, final List<String> titles) {
        final TaskList list = listRepository.findById(listId)
                .orElseThrow(() -> new IllegalArgumentException("List not found: " + listId));
        final List<Task> created = titles.stream()
                .filter(title -> title != null && !title.isBlank())
                .map(title -> taskRepository.save(Task.create(list, title.strip())))
                .toList();
        log.info("Bulk-created {} tasks in list={} owner={}", created.size(), list.getId(), list.getOwnerId());
        return created;
    }

    @Transactional
    public Task completeTask(final UUID taskId) {
        final Task task = require(taskId);
        if (task.getStatus() == TaskStatus.DONE) {
            throw new IllegalStateException("Task already done: " + taskId);
        }
        task.setStatus(TaskStatus.DONE);
        log.info("Completed task id={}", taskId);
        return task;
    }

    /**
     * Idempotently marks a task DONE. Unlike {@link #completeTask(UUID)} a repeat call is a no-op
     * (a {@code changed=false} result), which is what an inline ✅ button needs: a double tap or a
     * replayed callback must not toggle state twice.
     */
    @Transactional
    public TaskToggle markDone(final UUID taskId) {
        final Task task = require(taskId);
        final boolean changed = task.getStatus() != TaskStatus.DONE;
        if (changed) {
            task.setStatus(TaskStatus.DONE);
            log.info("Marked task done id={}", taskId);
        }
        return new TaskToggle(task, changed);
    }

    /** Idempotently reverts a task to TODO — the ↩️ undo of {@link #markDone(UUID)}. */
    @Transactional
    public TaskToggle reopen(final UUID taskId) {
        final Task task = require(taskId);
        final boolean changed = task.getStatus() != TaskStatus.TODO;
        if (changed) {
            task.setStatus(TaskStatus.TODO);
            log.info("Reopened task id={}", taskId);
        }
        return new TaskToggle(task, changed);
    }

    /** Result of a status toggle: the affected task and whether this call actually changed it. */
    public record TaskToggle(Task task, boolean changed) {
    }

    /** Pushes the due date to {@code now + by}; only actionable (TODO) tasks can be snoozed. */
    @Transactional
    public Task snoozeTask(final UUID taskId, final Duration by) {
        if (by == null || by.isZero() || by.isNegative()) {
            throw new IllegalArgumentException("Snooze duration must be positive");
        }
        final Task task = require(taskId);
        if (task.getStatus() != TaskStatus.TODO) {
            throw new IllegalStateException("Only TODO tasks can be snoozed: " + taskId);
        }
        task.setDueAt(Instant.now().plus(by));
        log.info("Snoozed task id={} until={}", taskId, task.getDueAt().orElseThrow());
        return task;
    }

    @Transactional
    public void deleteTask(final UUID taskId) {
        final Task task = require(taskId);
        if (task.getArchivedAt() == null) {
            task.setArchivedAt(Instant.now());
            log.info("Soft-deleted task id={}", taskId);
        }
    }

    /** Count of an owner's live (non-archived) tasks in the given list. */
    @Transactional(readOnly = true)
    public long countActiveTasks(final UUID listId) {
        return taskRepository.countByListIdAndArchivedAtIsNull(listId);
    }

    /** An owner's still-actionable tasks due during their local calendar day in {@code zone}. */
    @Transactional(readOnly = true)
    public List<Task> findToday(final UUID ownerId, final ZoneId zone) {
        final LocalDate today = LocalDate.now(zone);
        final Instant startInclusive = today.atStartOfDay(zone).toInstant();
        final Instant endExclusive = today.plusDays(1).atStartOfDay(zone).toInstant();
        return taskRepository.findDueBetween(ownerId, startInclusive, endExclusive);
    }

    /** An owner's still-actionable tasks whose due date has already passed. */
    @Transactional(readOnly = true)
    public List<Task> findOverdue(final UUID ownerId) {
        return taskRepository.findOverdueByOwner(ownerId, Instant.now());
    }

    /**
     * An owner's still-actionable tasks due within the next seven days — the half-open window
     * {@code [today 00:00, today+7 00:00)} in {@code zone}, so it includes everything left for today
     * plus the following six days.
     */
    @Transactional(readOnly = true)
    public List<Task> findWeek(final UUID ownerId, final ZoneId zone) {
        final LocalDate today = LocalDate.now(zone);
        final Instant startInclusive = today.atStartOfDay(zone).toInstant();
        final Instant endExclusive = today.plusDays(7).atStartOfDay(zone).toInstant();
        return taskRepository.findDueBetween(ownerId, startInclusive, endExclusive);
    }

    /** Looks up a single task by id (including archived ones); empty when no such task exists. */
    @Transactional(readOnly = true)
    public Optional<Task> find(final UUID taskId) {
        return taskRepository.findById(taskId);
    }

    private Task require(final UUID taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
    }
}
