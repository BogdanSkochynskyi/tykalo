package io.tykalo.list.handler;

import io.tykalo.list.CurrentContextService;
import io.tykalo.list.ListMessageService;
import io.tykalo.list.ListType;
import io.tykalo.list.TaskList;
import io.tykalo.list.TaskService;
import io.tykalo.telegram.MessageHandler;
import io.tykalo.user.User;
import io.tykalo.user.UserService;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;

/**
 * Bulk-add: a plain multi-line message becomes one task per line in the current list. Each
 * non-blank line is a task title; blank lines are ignored. Single-line messages are left alone
 * (returned as unclaimed) so non-command chatter stays silent.
 *
 * <p>Bulk-add only targets quick-capture lists — {@link ListType#CHECKLIST} and
 * {@link ListType#INBOX}. For a PROJECT or ROUTINE current list it shows a hint and creates
 * nothing, since those lists expect full tasks added one at a time.
 *
 * <p>On success it ensures the list's live, editable message exists (via
 * {@link ListMessageService#publishIfAbsent}) and stays silent ({@code Optional.empty()}) — that
 * single self-updating message is the feedback, rather than a separate text acknowledgement. The
 * content refresh itself rides the {@code ListChangedEvent} that {@code createTasks} fires, so the
 * handler only has to guarantee the message exists, not re-render it.
 */
@Component
@RequiredArgsConstructor
public class BulkAddHandler implements MessageHandler {

    private final UserService userService;
    private final CurrentContextService currentContext;
    private final TaskService taskService;
    private final ListMessageService listMessageService;

    @Override
    public Optional<String> handle(final Update update) {
        final Message message = update.getMessage();
        if (message == null || message.getText() == null) {
            return Optional.empty();
        }
        final List<String> titles = nonBlankLines(message.getText());
        if (titles.size() < 2) {
            return Optional.empty();
        }
        final User user = userService.findOrCreate(update);
        final UUID ownerId = Objects.requireNonNull(user.getId());
        final Optional<TaskList> target = currentContext.resolveCurrentList(ownerId);
        if (target.isEmpty()) {
            return Optional.of("No list to add to. Create one with /list create <name>.");
        }
        final TaskList list = target.get();
        if (!supportsBulkAdd(list.getType())) {
            return Optional.of(hintFor(list));
        }
        taskService.createTasks(Objects.requireNonNull(list.getId()), titles);
        listMessageService.publishIfAbsent(list, message.getChatId());
        return Optional.empty();
    }

    private List<String> nonBlankLines(final String text) {
        return text.lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .toList();
    }

    private boolean supportsBulkAdd(final ListType type) {
        return type == ListType.CHECKLIST || type == ListType.INBOX;
    }

    private String hintFor(final TaskList list) {
        return ("⚠️ \"%s\" is a %s list — bulk-add works only in checklists and your inbox.%n"
                + "Add tasks one at a time with /add, or switch lists with /use.")
                .formatted(list.getName(), list.getType());
    }
}
