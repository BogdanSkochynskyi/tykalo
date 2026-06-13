package io.tykalo.list;

import io.tykalo.user.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
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
    private final UserRepository userRepository;
    private final RecurrenceCalculator recurrenceCalculator;
    private final ApplicationEventPublisher eventPublisher;

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
        eventPublisher.publishEvent(new TaskCreatedEvent(saved, list.getType()));
        announceChange(listId);
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
        created.forEach(task -> eventPublisher.publishEvent(new TaskCreatedEvent(task, list.getType())));
        log.info("Bulk-created {} tasks in list={} owner={}", created.size(), list.getId(), list.getOwnerId());
        if (!created.isEmpty()) {
            announceChange(listId);
        }
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
        expandRecurrence(task);
        announceChange(task.getListId());
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
            expandRecurrence(task);
            announceChange(task.getListId());
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
            announceChange(task.getListId());
        }
        return new TaskToggle(task, changed);
    }

    /** Result of a status toggle: the affected task and whether this call actually changed it. */
    public record TaskToggle(Task task, boolean changed) {
    }

    /** Replaces a task's title; the title must not be blank (it is the one required field). */
    @Transactional
    public Task updateTitle(final UUID taskId, final String title) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Task title must not be blank");
        }
        final Task task = require(taskId);
        task.setTitle(title.strip());
        log.info("Updated title of task id={}", taskId);
        announceChange(task.getListId());
        return task;
    }

    /** Replaces a task's description (stored in UTC-agnostic text); blank clears it to {@code null}. */
    @Transactional
    public Task updateDescription(final UUID taskId, final String description) {
        final Task task = require(taskId);
        task.setDescription(description == null || description.isBlank() ? null : description.strip());
        log.info("Updated description of task id={}", taskId);
        announceChange(task.getListId());
        return task;
    }

    /** Sets a task's deadline ({@code dueAt} is stored in UTC). */
    @Transactional
    public Task updateDueAt(final UUID taskId, final Instant dueAt) {
        final Task task = require(taskId);
        task.setDueAt(dueAt);
        log.info("Updated dueAt of task id={} to {}", taskId, dueAt);
        announceChange(task.getListId());
        return task;
    }

    /** Sets a task's priority. */
    @Transactional
    public Task updatePriority(final UUID taskId, final Priority priority) {
        final Task task = require(taskId);
        task.setPriority(priority);
        log.info("Updated priority of task id={} to {}", taskId, priority);
        announceChange(task.getListId());
        return task;
    }

    /** Pushes the due date to {@code now + by}; only actionable (TODO) tasks can be snoozed. */
    @Transactional
    public Task snoozeTask(final UUID taskId, final Duration by) {
        if (by == null || by.isZero() || by.isNegative()) {
            throw new IllegalArgumentException("Snooze duration must be positive");
        }
        return snoozeUntil(taskId, Instant.now().plus(by));
    }

    /**
     * Pushes the due date to an explicit future {@code until}; the date-anchored sibling of
     * {@link #snoozeTask(UUID, Duration)} for keyword targets ("tomorrow", "next week"). Only
     * actionable (TODO) tasks can be snoozed.
     */
    @Transactional
    public Task snoozeUntil(final UUID taskId, final Instant until) {
        if (until == null || !until.isAfter(Instant.now())) {
            throw new IllegalArgumentException("Snooze target must be in the future");
        }
        final Task task = require(taskId);
        if (task.getStatus() != TaskStatus.TODO) {
            throw new IllegalStateException("Only TODO tasks can be snoozed: " + taskId);
        }
        task.setDueAt(until);
        log.info("Snoozed task id={} until={}", taskId, until);
        announceChange(task.getListId());
        return task;
    }

    @Transactional
    public void deleteTask(final UUID taskId) {
        final Task task = require(taskId);
        if (task.getArchivedAt() == null) {
            task.setArchivedAt(Instant.now());
            log.info("Soft-deleted task id={}", taskId);
            announceChange(task.getListId());
        }
    }

    /** Count of an owner's live (non-archived) tasks in the given list. */
    @Transactional(readOnly = true)
    public long countActiveTasks(final UUID listId) {
        return taskRepository.countByListIdAndArchivedAtIsNull(listId);
    }

    /** Live-task counts for a list: total non-archived and how many of those are DONE. */
    @Transactional(readOnly = true)
    public Counts counts(final UUID listId) {
        final long total = taskRepository.countByListIdAndArchivedAtIsNull(listId);
        final long done = taskRepository.countByListIdAndStatusAndArchivedAtIsNull(listId, TaskStatus.DONE);
        return new Counts(total, done);
    }

    /** A list's live-task tally: {@code total} non-archived tasks, of which {@code done} are complete. */
    public record Counts(long total, long done) {
    }

    /** An owner's still-actionable tasks due during their local calendar day in {@code zone}. */
    @Transactional(readOnly = true)
    public List<Task> findToday(final UUID ownerId, final ZoneId zone) {
        final LocalDate today = LocalDate.now(zone);
        final Instant startInclusive = today.atStartOfDay(zone).toInstant();
        final Instant endExclusive = today.plusDays(1).atStartOfDay(zone).toInstant();
        return taskRepository.findDueBetween(ownerId, startInclusive, endExclusive);
    }

    /**
     * An owner's still-actionable {@code PROJECT}-list tasks due during their local calendar day in
     * {@code zone} — the morning digest's content, ordered by due time then title.
     */
    @Transactional(readOnly = true)
    public List<Task> findProjectTasksDueToday(final UUID ownerId, final ZoneId zone) {
        final LocalDate today = LocalDate.now(zone);
        final Instant startInclusive = today.atStartOfDay(zone).toInstant();
        final Instant endExclusive = today.plusDays(1).atStartOfDay(zone).toInstant();
        return taskRepository.findProjectTasksDueBetween(ownerId, startInclusive, endExclusive);
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

    /**
     * On completion of a recurring task (TK-146) creates its next instance in the same transaction.
     * Skips when the task has no recurrence rule, no {@code dueAt} to anchor from, or was manually
     * archived. The next due date is computed by {@link RecurrenceCalculator} in the owner's timezone
     * (falling back to UTC), so the wall-clock time-of-day carries over.
     */
    private void expandRecurrence(final Task completed) {
        if (completed.getArchivedAt() != null
                || completed.getRecurrenceRule().isEmpty()
                || completed.getDueAt().isEmpty()) {
            return;
        }
        final ZoneId zone = ownerZone(completed.getOwnerId());
        final Optional<Instant> nextDueAt = recurrenceCalculator.nextOccurrence(
                completed.getRecurrenceRule().get(), completed.getDueAt().get(), zone);
        if (nextDueAt.isEmpty()) {
            log.debug("No next occurrence for task id={} rule={}", completed.getId(),
                    completed.getRecurrenceRule().get());
            return;
        }
        final Task next = taskRepository.save(Task.recurringInstance(completed, nextDueAt.get()));
        log.info("Recurring task id={} spawned next instance id={} dueAt={}",
                completed.getId(), next.getId(), nextDueAt.get());
    }

    /**
     * Announces that {@code listId}'s task state changed so the live Telegram message(s) for the list
     * get re-rendered. Fired from every mutator; the {@code list} package's listener (see
     * {@link ListMessageService#onListChanged}) handles it after the surrounding transaction commits.
     */
    private void announceChange(final UUID listId) {
        eventPublisher.publishEvent(new ListChangedEvent(listId));
    }

    private ZoneId ownerZone(final UUID ownerId) {
        return userRepository.findById(ownerId)
                .map(user -> user.getTimezone() == null ? (ZoneId) ZoneOffset.UTC : user.getTimezone())
                .orElse(ZoneOffset.UTC);
    }

    private Task require(final UUID taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
    }
}
