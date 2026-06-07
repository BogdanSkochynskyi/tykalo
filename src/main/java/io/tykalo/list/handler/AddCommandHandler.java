package io.tykalo.list.handler;

import io.tykalo.list.CurrentContextService;
import io.tykalo.list.DueDateParser;
import io.tykalo.list.RecurrenceParser;
import io.tykalo.list.Task;
import io.tykalo.list.TaskList;
import io.tykalo.list.TaskService;
import io.tykalo.telegram.TelegramCommand;
import io.tykalo.user.User;
import io.tykalo.user.UserService;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;

/**
 * The {@code /add} command: quick-captures a title-only task (no due date) into the current list.
 *
 * <p>The target is resolved by {@link CurrentContextService#resolveCurrentList(UUID)} — the
 * per-user current list if one is set, otherwise the Inbox. The reply names the task's id and the
 * list it landed in. A leading deadline (ISO or basic natural language, e.g. {@code tomorrow 9am})
 * is parsed off the title by {@link DueDateParser} and stored as {@code dueAt}; a past deadline is
 * still accepted with a warning. A leading or trailing recurrence keyword (e.g. {@code daily},
 * {@code weekends}, {@code every Monday}) is parsed off by {@link RecurrenceParser} and stored as a
 * short rule on the task. Nudgers are layered on in later tickets.
 */
@Component
@RequiredArgsConstructor
public class AddCommandHandler {

    static final int MAX_TITLE_LENGTH = 500;

    private static final DateTimeFormatter DUE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final UserService userService;
    private final CurrentContextService currentContext;
    private final TaskService taskService;
    private final DueDateParser dueDateParser;
    private final RecurrenceParser recurrenceParser;

    @TelegramCommand("/add")
    public String add(final Update update) {
        final String args = argsOf(update);
        if (args.isBlank()) {
            return "Usage: /add <title>";
        }
        final User user = userService.findOrCreate(update);
        final ZoneId zone = Optional.ofNullable(user.getTimezone()).orElse(ZoneOffset.UTC);
        final Instant now = Instant.now();
        final DueDateParser.Result parsed = dueDateParser.parse(args, zone, now);
        final RecurrenceParser.Result recurrence = recurrenceParser.parse(parsed.title());
        final String title = recurrence.title();
        if (title.isBlank()) {
            return "Usage: /add <title> — I found a date but no task text.";
        }
        if (title.length() > MAX_TITLE_LENGTH) {
            return "⚠️ Title is too long (%d chars). Keep it under %d."
                    .formatted(title.length(), MAX_TITLE_LENGTH);
        }
        final UUID ownerId = Objects.requireNonNull(user.getId());
        final Optional<TaskList> target = currentContext.resolveCurrentList(ownerId);
        if (target.isEmpty()) {
            return "No list to add to. Create one with /list create <name>.";
        }
        final TaskList list = target.get();
        final Task task = taskService.createTask(
                Objects.requireNonNull(list.getId()), title, parsed.dueAt(), recurrence.recurrenceRule());
        return reply(list, task, parsed.dueAt(), recurrence.recurrenceRule(), zone, now);
    }

    private String reply(final TaskList list, final Task task, final @Nullable Instant dueAt,
                         final @Nullable String recurrenceRule, final ZoneId zone, final Instant now) {
        final StringBuilder out =
                new StringBuilder("✅ Added to \"%s\" — task %s".formatted(list.getName(), task.getId()));
        if (dueAt != null) {
            final String due = DUE_FORMAT.format(dueAt.atZone(zone));
            final String past = dueAt.isBefore(now) ? " ⚠️ (in the past)" : "";
            out.append("%n🗓 due %s%s".formatted(due, past));
        }
        if (recurrenceRule != null) {
            out.append("%n🔁 repeats %s".formatted(RecurrenceParser.describe(recurrenceRule)));
        }
        return out.toString();
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
