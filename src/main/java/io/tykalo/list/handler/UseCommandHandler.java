package io.tykalo.list.handler;

import io.tykalo.list.CurrentContextService;
import io.tykalo.list.ListService;
import io.tykalo.list.TaskList;
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
 * The {@code /use} command: sets or shows the per-user current list context.
 *
 * <p>{@code /use <name>} switches the current list so later unqualified {@code /add} commands land
 * there; {@code /use} with no argument reports the current context, falling back to the Inbox.
 */
@Component
@RequiredArgsConstructor
public class UseCommandHandler {

    private final UserService userService;
    private final ListService listService;
    private final CurrentContextService currentContext;

    @TelegramCommand("/use")
    public String use(final Update update) {
        final User user = userService.findOrCreate(update);
        final UUID ownerId = Objects.requireNonNull(user.getId());
        final String name = argsOf(update);
        if (name.isBlank()) {
            return showCurrent(ownerId);
        }
        final Optional<TaskList> target = listService.findActiveByName(ownerId, name);
        if (target.isEmpty()) {
            return "No active list named \"" + name + "\". Use /lists to see your lists.";
        }
        final TaskList list = target.get();
        currentContext.set(ownerId, Objects.requireNonNull(list.getId()));
        return "✅ Switched to \"%s\". New tasks go here.".formatted(list.getName());
    }

    private String showCurrent(final UUID ownerId) {
        final Optional<TaskList> explicit = currentContext.get(ownerId)
                .flatMap(listService::getActiveById);
        if (explicit.isPresent()) {
            return "📍 Current list: \"%s\".".formatted(explicit.get().getName());
        }
        return listService.findInbox(ownerId)
                .map(inbox -> "📍 Current list: \"%s\" (default).%nSwitch with /use <name>."
                        .formatted(inbox.getName()))
                .orElse("📍 No current list. Switch with /use <name>.");
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
