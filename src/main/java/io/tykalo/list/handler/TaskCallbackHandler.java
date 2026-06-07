package io.tykalo.list.handler;

import io.tykalo.list.ListMessageService;
import io.tykalo.list.ListService;
import io.tykalo.list.TaskList;
import io.tykalo.list.TaskService;
import io.tykalo.list.TaskService.TaskToggle;
import io.tykalo.telegram.CallbackHandler;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.message.MaybeInaccessibleMessage;

/**
 * Handles the inline ✅/↩️ buttons on a list's live message. {@code task:done:{id}} marks the task
 * DONE; {@code task:undo:{id}} reverts it to TODO. After a real change the list message is refreshed
 * in place (via {@link ListMessageService} — the Telegram Edit Message API), so the whole toggle
 * happens inside the one message with no extra chatter.
 *
 * <p>Both toggles are idempotent: a replayed or double-tapped callback that finds the task already in
 * the target state is a no-op (no second state change, no redundant edit) but still answers with a
 * toast. Callbacks whose data is not a {@code task:} action are left unclaimed.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TaskCallbackHandler implements CallbackHandler {

    private static final String DONE_PREFIX = "task:done:";
    private static final String UNDO_PREFIX = "task:undo:";

    private final TaskService taskService;
    private final ListMessageService listMessageService;
    private final ListService listService;

    @Override
    public Optional<String> handle(final CallbackQuery callback) {
        final String data = callback.getData();
        if (data == null) {
            return Optional.empty();
        }
        if (data.startsWith(DONE_PREFIX)) {
            return toggle(data.substring(DONE_PREFIX.length()), callback, true);
        }
        if (data.startsWith(UNDO_PREFIX)) {
            return toggle(data.substring(UNDO_PREFIX.length()), callback, false);
        }
        return Optional.empty();
    }

    private Optional<String> toggle(final String rawId, final CallbackQuery callback, final boolean done) {
        final UUID taskId = parseId(rawId);
        if (taskId == null) {
            log.warn("Ignoring callback with unparseable task id: {}", callback.getData());
            return Optional.of("Unknown task");
        }
        final TaskToggle result = done ? taskService.markDone(taskId) : taskService.reopen(taskId);
        if (result.changed()) {
            refreshList(result.task().getListId(), chatIdOf(callback));
        }
        return Optional.of(done ? "Done!" : "Reopened");
    }

    private void refreshList(final UUID listId, final @Nullable Long chatId) {
        if (chatId == null) {
            log.warn("No chat id on callback for list {} — cannot refresh the live message", listId);
            return;
        }
        final Optional<TaskList> list = listService.getById(listId);
        if (list.isEmpty()) {
            log.warn("List {} not found while refreshing its live message", listId);
            return;
        }
        listMessageService.publish(list.get(), chatId);
    }

    private @Nullable Long chatIdOf(final CallbackQuery callback) {
        final MaybeInaccessibleMessage message = callback.getMessage();
        return message == null ? null : message.getChatId();
    }

    private @Nullable UUID parseId(final String rawId) {
        try {
            return UUID.fromString(rawId);
        } catch (final IllegalArgumentException e) {
            return null;
        }
    }
}
