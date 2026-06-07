package io.tykalo.list.handler;

import io.tykalo.list.DueDateParser;
import io.tykalo.list.Priority;
import io.tykalo.list.SnoozeParser;
import io.tykalo.list.Task;
import io.tykalo.list.TaskService;
import io.tykalo.list.TaskStatus;
import io.tykalo.telegram.TelegramCommand;
import io.tykalo.user.User;
import io.tykalo.user.UserService;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;

/**
 * Task-modification commands, each addressing a task by the id that {@code /add} reports:
 *
 * <ul>
 *   <li>{@code /edit <id> <field> <value>} — sets {@code title}, {@code description}, {@code due}
 *       (parsed by {@link DueDateParser}) or {@code priority};</li>
 *   <li>{@code /snooze <id> <duration>} — pushes the deadline by {@code 1h}/{@code 2d}/{@code
 *       tomorrow}/{@code next week} (parsed by {@link SnoozeParser}); only open tasks qualify;</li>
 *   <li>{@code /delete <id>} — archives the task after a stateless text confirmation
 *       ({@code /delete <id> confirm}), mirroring {@code /list delete} since dialog state and inline
 *       callbacks do not exist yet.</li>
 * </ul>
 *
 * <p>All three validate at the boundary and share one owned-task resolver that reuses {@code
 * /done}'s vocabulary: a blank, malformed, unknown/archived or someone-else's id each get a distinct
 * message. The handler resolves the user, parses the request and delegates the mutation to
 * {@link TaskService}; due times render in the user's timezone (UTC when unset).
 */
@Component
@RequiredArgsConstructor
public class TaskModificationCommandHandler {

    static final int MAX_TITLE_LENGTH = 500;
    private static final String CONFIRM = "confirm";
    private static final DateTimeFormatter DUE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final UserService userService;
    private final TaskService taskService;
    private final DueDateParser dueDateParser;
    private final SnoozeParser snoozeParser;

    @TelegramCommand("/edit")
    public String edit(final Update update) {
        final String[] parts = argsOf(update).split("\\s+", 3);
        if (parts.length < 3 || parts[0].isBlank()) {
            return "Usage: /edit <id> <field> <value> — field is title, description, due or priority.";
        }
        final User user = userService.findOrCreate(update);
        final Resolution resolved = resolve(parts[0], user);
        if (resolved.error() != null) {
            return resolved.error();
        }
        final Task task = Objects.requireNonNull(resolved.task());
        final String field = parts[1].toLowerCase(Locale.ROOT);
        final String value = parts[2].strip();
        return switch (field) {
            case "title" -> editTitle(task, value);
            case "description", "desc" -> editDescription(task, value);
            case "due" -> editDue(task, value, zoneOf(user));
            case "priority", "prio" -> editPriority(task, value);
            default -> "⚠️ Unknown field \"%s\". Valid fields: title, description, due, priority."
                    .formatted(parts[1]);
        };
    }

    @TelegramCommand("/snooze")
    public String snooze(final Update update) {
        final String[] parts = argsOf(update).split("\\s+", 2);
        if (parts.length < 2 || parts[0].isBlank()) {
            return "Usage: /snooze <id> <duration> — e.g. 1h, 2d, tomorrow, next week.";
        }
        final User user = userService.findOrCreate(update);
        final Resolution resolved = resolve(parts[0], user);
        if (resolved.error() != null) {
            return resolved.error();
        }
        final Task task = Objects.requireNonNull(resolved.task());
        if (task.getStatus() != TaskStatus.TODO) {
            return "You can only snooze open tasks.";
        }
        final ZoneId zone = zoneOf(user);
        final Optional<Instant> target = snoozeParser.parse(parts[1], zone, Instant.now());
        if (target.isEmpty()) {
            return "⚠️ I couldn't read a duration from \"%s\". Try 1h, 2d, tomorrow or next week."
                    .formatted(parts[1].strip());
        }
        taskService.snoozeUntil(Objects.requireNonNull(task.getId()), target.get());
        return "😴 Snoozed \"%s\" until %s.".formatted(task.getTitle(), DUE_FORMAT.format(target.get().atZone(zone)));
    }

    @TelegramCommand("/delete")
    public String delete(final Update update) {
        final String args = argsOf(update);
        if (args.isBlank()) {
            return "Usage: /delete <id> — asks for confirmation before archiving.";
        }
        final String[] parts = args.split("\\s+");
        final boolean confirmed = parts.length > 1 && parts[parts.length - 1].equalsIgnoreCase(CONFIRM);
        final User user = userService.findOrCreate(update);
        final Resolution resolved = resolve(parts[0], user);
        if (resolved.error() != null) {
            return resolved.error();
        }
        final Task task = Objects.requireNonNull(resolved.task());
        if (!confirmed) {
            return """
                    ⚠️ Delete "%s"? This archives it.
                    To confirm, send: /delete %s confirm"""
                    .formatted(task.getTitle(), task.getId());
        }
        taskService.deleteTask(Objects.requireNonNull(task.getId()));
        return "🗑️ Deleted \"%s\".".formatted(task.getTitle());
    }

    private String editTitle(final Task task, final String value) {
        if (value.isBlank()) {
            return "⚠️ Title must not be blank.";
        }
        if (value.length() > MAX_TITLE_LENGTH) {
            return "⚠️ Title is too long (%d chars). Keep it under %d.".formatted(value.length(), MAX_TITLE_LENGTH);
        }
        taskService.updateTitle(Objects.requireNonNull(task.getId()), value);
        return "✏️ Renamed to \"%s\".".formatted(value.strip());
    }

    private String editDescription(final Task task, final String value) {
        taskService.updateDescription(Objects.requireNonNull(task.getId()), value);
        return "✏️ Updated description of \"%s\".".formatted(task.getTitle());
    }

    private String editDue(final Task task, final String value, final ZoneId zone) {
        final Instant now = Instant.now();
        final DueDateParser.Result parsed = dueDateParser.parse(value, zone, now);
        if (!parsed.hasDueDate()) {
            return "⚠️ I couldn't read a date from \"%s\". Try 2026-06-15 14:00, tomorrow 9am or in 2 days."
                    .formatted(value);
        }
        final Instant dueAt = Objects.requireNonNull(parsed.dueAt());
        taskService.updateDueAt(Objects.requireNonNull(task.getId()), dueAt);
        final String past = dueAt.isBefore(now) ? " ⚠️ (in the past)" : "";
        return "🗓 Due of \"%s\" set to %s%s.".formatted(task.getTitle(), DUE_FORMAT.format(dueAt.atZone(zone)), past);
    }

    private String editPriority(final Task task, final String value) {
        final Optional<Priority> priority = parsePriority(value);
        if (priority.isEmpty()) {
            return "⚠️ Priority must be LOW, MEDIUM, HIGH or URGENT.";
        }
        taskService.updatePriority(Objects.requireNonNull(task.getId()), priority.get());
        return "✏️ Priority of \"%s\" set to %s.".formatted(task.getTitle(), priority.get());
    }

    private Optional<Priority> parsePriority(final String value) {
        for (final Priority priority : Priority.values()) {
            if (priority.name().equalsIgnoreCase(value.strip())) {
                return Optional.of(priority);
            }
        }
        return Optional.empty();
    }

    /** Resolves a raw id to the caller's own live task, or an error message mirroring {@code /done}. */
    private Resolution resolve(final String rawId, final User user) {
        final UUID taskId;
        try {
            taskId = UUID.fromString(rawId.strip());
        } catch (final IllegalArgumentException e) {
            return Resolution.error("⚠️ \"%s\" is not a valid task id.".formatted(rawId.strip()));
        }
        final Optional<Task> found = taskService.find(taskId);
        if (found.isEmpty() || found.get().getArchivedAt() != null) {
            return Resolution.error("Task not found.");
        }
        final Task task = found.get();
        if (!task.getOwnerId().equals(user.getId())) {
            return Resolution.error("That task isn't yours.");
        }
        return Resolution.ok(task);
    }

    private record Resolution(@Nullable Task task, @Nullable String error) {

        static Resolution ok(final Task task) {
            return new Resolution(task, null);
        }

        static Resolution error(final String error) {
            return new Resolution(null, error);
        }
    }

    private ZoneId zoneOf(final User user) {
        return Optional.ofNullable(user.getTimezone()).orElse(ZoneOffset.UTC);
    }

    private String argsOf(final Update update) {
        final Message message = update.getMessage();
        final String text = message == null ? null : message.getText();
        if (text == null) {
            return "";
        }
        final String[] parts = text.strip().split("\\s+", 2);
        return parts.length > 1 ? parts[1].strip() : "";
    }
}
