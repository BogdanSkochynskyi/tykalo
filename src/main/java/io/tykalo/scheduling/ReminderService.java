package io.tykalo.scheduling;

import io.tykalo.list.ListRenderer;
import io.tykalo.list.Task;
import io.tykalo.list.TaskRepository;
import io.tykalo.telegram.TelegramMessageGateway;
import io.tykalo.user.QuietHoursService;
import io.tykalo.user.User;
import io.tykalo.user.UserRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

/**
 * Sends overdue-task reminders to task owners. Driven every 15 minutes by {@link ReminderJob}: for
 * the given instant it walks every overdue {@code PROJECT} task and, per task, sends the highest
 * {@link ReminderLevel} (+2h/+6h/+12h past {@code due_at}) that has elapsed but not yet been sent.
 * Splitting this out of the job keeps it unit-testable without Quartz/ShedLock.
 *
 * <p>"Max sent level" rule: a reminder is sent only when the highest elapsed level exceeds the
 * highest level already in {@code reminder_log} for that task. This means a level is never sent twice
 * and, after downtime, a single most-advanced reminder is sent rather than a burst. A user inside
 * their quiet hours is skipped entirely for this tick (no send, no log), so the next post-quiet sweep
 * delivers the then-current level — i.e. quiet hours postpone rather than drop the reminder.
 *
 * <p>Each send is isolated: a failure delivering to one user is logged and the sweep continues. The
 * reminder reuses the list's {@code task:done:{id}} inline button (via {@link ListRenderer}), so taps
 * work without any new callback wiring.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReminderService {

    private final TaskRepository taskRepository;
    private final UserRepository userRepository;
    private final ReminderLogRepository reminderLogRepository;
    private final QuietHoursService quietHoursService;
    private final ReminderRenderer renderer;
    private final ListRenderer listRenderer;
    private final TelegramMessageGateway gateway;

    /** Sends the due reminder for every overdue PROJECT task that has crossed a new reminder tier. */
    @Transactional
    public void sendDueReminders(final Instant now) {
        final List<Task> overdue = taskRepository.findOverdueProjectTasks(now);
        if (overdue.isEmpty()) {
            return;
        }
        final Map<UUID, User> owners = ownersOf(overdue);
        final Map<UUID, Integer> maxSentByTask = maxSentLevels(overdue);

        int sent = 0;
        for (final Task task : overdue) {
            if (sendIfDue(task, owners, maxSentByTask, now)) {
                sent++;
            }
        }
        log.info("Reminder sweep at {} over {} overdue task(s) sent {} reminder(s)", now, overdue.size(), sent);
    }

    private boolean sendIfDue(final Task task, final Map<UUID, User> owners,
                              final Map<UUID, Integer> maxSentByTask, final Instant now) {
        final User owner = owners.get(task.getOwnerId());
        if (owner == null) {
            log.warn("Skipping reminder for task id={} — owner id={} not found", task.getId(), task.getOwnerId());
            return false;
        }
        if (quietHoursService.isQuiet(owner, now)) {
            return false;
        }
        final Optional<ReminderLevel> elapsed = ReminderLevel.maxElapsed(task.getDueAt().orElseThrow(), now);
        if (elapsed.isEmpty()) {
            return false;
        }
        final ReminderLevel level = elapsed.get();
        if (level.level() <= maxSentByTask.getOrDefault(task.getId(), 0)) {
            return false;
        }
        return send(owner, task, level, now);
    }

    private boolean send(final User owner, final Task task, final ReminderLevel level, final Instant now) {
        try {
            final String body = renderer.render(task, now);
            final InlineKeyboardMarkup keyboard = listRenderer.keyboard(List.of(task));
            gateway.sendMarkdown(owner.getTgChatId(), body, keyboard);
            reminderLogRepository.save(ReminderLog.of(task.getId(), level, now));
            log.info("Sent reminder level={} for task id={} to user id={}", level.level(), task.getId(), owner.getId());
            return true;
        } catch (final RuntimeException e) {
            log.warn("Failed to send reminder for task id={} to user id={}", task.getId(), owner.getId(), e);
            return false;
        }
    }

    private Map<UUID, User> ownersOf(final List<Task> tasks) {
        final List<UUID> ownerIds = tasks.stream().map(Task::getOwnerId).distinct().toList();
        return userRepository.findAllById(ownerIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
    }

    private Map<UUID, Integer> maxSentLevels(final List<Task> tasks) {
        final List<UUID> taskIds = tasks.stream().map(Task::getId).toList();
        final Map<UUID, Integer> maxByTask = new HashMap<>();
        for (final ReminderLog logEntry : reminderLogRepository.findByTaskIdIn(taskIds)) {
            maxByTask.merge(logEntry.getTaskId(), logEntry.getLevel(), Math::max);
        }
        return maxByTask;
    }
}
