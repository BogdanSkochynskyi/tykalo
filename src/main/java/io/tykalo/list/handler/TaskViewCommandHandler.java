package io.tykalo.list.handler;

import io.tykalo.list.ListService;
import io.tykalo.list.Task;
import io.tykalo.list.TaskService;
import io.tykalo.list.TaskViewRenderer;
import io.tykalo.telegram.TelegramCommand;
import io.tykalo.user.User;
import io.tykalo.user.UserService;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;

/**
 * Read-side task commands plus the textual completion shortcut:
 *
 * <ul>
 *   <li>{@code /today} — tasks due during the user's local day, grouped by list;</li>
 *   <li>{@code /overdue} — still-actionable tasks past their due date, grouped by list;</li>
 *   <li>{@code /week} — tasks due over the next seven days, grouped by day;</li>
 *   <li>{@code /done <id>} — marks a task done by id (the id {@code /add} reports), the text
 *       alternative to the inline ✅ button on the live list.</li>
 * </ul>
 *
 * <p>All due times render in the user's timezone (UTC when none is set). The actual formatting
 * lives in {@link TaskViewRenderer}; this handler only resolves the user, the tasks and the list
 * names. {@code /done} validates input at the boundary: a missing or malformed id, an unknown or
 * archived task and a task owned by someone else each get a distinct message, and completion goes
 * through the idempotent {@link TaskService#markDone(UUID)} so a repeat is a no-op.
 */
@Component
@RequiredArgsConstructor
public class TaskViewCommandHandler {

    private final UserService userService;
    private final TaskService taskService;
    private final ListService listService;
    private final TaskViewRenderer renderer;

    @TelegramCommand("/today")
    public String today(final Update update) {
        final User user = userService.findOrCreate(update);
        final ZoneId zone = zoneOf(user);
        final List<Task> tasks = taskService.findToday(Objects.requireNonNull(user.getId()), zone);
        return renderer.today(tasks, listNames(tasks), zone);
    }

    @TelegramCommand("/overdue")
    public String overdue(final Update update) {
        final User user = userService.findOrCreate(update);
        final ZoneId zone = zoneOf(user);
        final List<Task> tasks = taskService.findOverdue(Objects.requireNonNull(user.getId()));
        return renderer.overdue(tasks, listNames(tasks), zone);
    }

    @TelegramCommand("/week")
    public String week(final Update update) {
        final User user = userService.findOrCreate(update);
        final ZoneId zone = zoneOf(user);
        final List<Task> tasks = taskService.findWeek(Objects.requireNonNull(user.getId()), zone);
        return renderer.week(tasks, zone);
    }

    @TelegramCommand("/done")
    public String done(final Update update) {
        final String arg = argOf(update);
        if (arg.isBlank()) {
            return "Usage: /done <id> — the task id shown when you added it.";
        }
        final UUID taskId;
        try {
            taskId = UUID.fromString(arg);
        } catch (final IllegalArgumentException e) {
            return "⚠️ \"%s\" is not a valid task id.".formatted(arg);
        }
        final User user = userService.findOrCreate(update);
        final Optional<Task> found = taskService.find(taskId);
        if (found.isEmpty() || found.get().getArchivedAt() != null) {
            return "Task not found.";
        }
        final Task task = found.get();
        if (!task.getOwnerId().equals(user.getId())) {
            return "That task isn't yours.";
        }
        final TaskService.TaskToggle toggle = taskService.markDone(taskId);
        if (!toggle.changed()) {
            return "✔️ \"%s\" was already done.".formatted(task.getTitle());
        }
        return "✅ Done: %s".formatted(task.getTitle());
    }

    private Map<UUID, String> listNames(final List<Task> tasks) {
        final List<UUID> listIds = tasks.stream().map(Task::getListId).distinct().toList();
        return listService.namesByIds(listIds);
    }

    private ZoneId zoneOf(final User user) {
        return Optional.ofNullable(user.getTimezone()).orElse(ZoneOffset.UTC);
    }

    private String argOf(final Update update) {
        final Message message = update.getMessage();
        final String text = message == null ? null : message.getText();
        if (text == null) {
            return "";
        }
        final String[] parts = text.strip().split("\\s+", 2);
        return parts.length > 1 ? parts[1].strip() : "";
    }
}
