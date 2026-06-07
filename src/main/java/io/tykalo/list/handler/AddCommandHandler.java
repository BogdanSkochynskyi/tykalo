package io.tykalo.list.handler;

import io.tykalo.list.CurrentContextService;
import io.tykalo.list.Task;
import io.tykalo.list.TaskList;
import io.tykalo.list.TaskService;
import io.tykalo.telegram.TelegramCommand;
import io.tykalo.user.User;
import io.tykalo.user.UserService;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;

/**
 * The {@code /add} command: quick-captures a title-only task (no due date) into the current list.
 *
 * <p>The target is resolved by {@link CurrentContextService#resolveCurrentList(UUID)} — the
 * per-user current list if one is set, otherwise the Inbox. The reply names the task's id and the
 * list it landed in. Adding deadlines, recurrence and Nudgers is layered on in later tickets
 * (TK-132+); this handler stays a plain quick-capture.
 */
@Component
@RequiredArgsConstructor
public class AddCommandHandler {

    static final int MAX_TITLE_LENGTH = 500;

    private final UserService userService;
    private final CurrentContextService currentContext;
    private final TaskService taskService;

    @TelegramCommand("/add")
    public String add(final Update update) {
        final String title = argsOf(update);
        if (title.isBlank()) {
            return "Usage: /add <title>";
        }
        if (title.length() > MAX_TITLE_LENGTH) {
            return "⚠️ Title is too long (%d chars). Keep it under %d."
                    .formatted(title.length(), MAX_TITLE_LENGTH);
        }
        final User user = userService.findOrCreate(update);
        final UUID ownerId = Objects.requireNonNull(user.getId());
        final Optional<TaskList> target = currentContext.resolveCurrentList(ownerId);
        if (target.isEmpty()) {
            return "No list to add to. Create one with /list create <name>.";
        }
        final TaskList list = target.get();
        final Task task = taskService.createTask(Objects.requireNonNull(list.getId()), title);
        return "✅ Added to \"%s\" — task %s".formatted(list.getName(), task.getId());
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
