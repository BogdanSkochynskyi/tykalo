package io.tykalo.list.handler;

import io.tykalo.list.TaskService;
import io.tykalo.telegram.CallbackHandler;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;

/**
 * Handles the inline ✅/↩️ buttons on a list's live message. {@code task:done:{id}} marks the task
 * DONE; {@code task:undo:{id}} reverts it to TODO. The live message is refreshed in place afterwards,
 * but that is not this handler's job: the mutation fires a {@code ListChangedEvent} and
 * {@code ListMessageService} edits every live message for the list, so the whole toggle happens inside
 * the one message with no extra chatter.
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
        if (done) {
            taskService.markDone(taskId);
        } else {
            taskService.reopen(taskId);
        }
        return Optional.of(done ? "Done!" : "Reopened");
    }

    private @Nullable UUID parseId(final String rawId) {
        try {
            return UUID.fromString(rawId);
        } catch (final IllegalArgumentException e) {
            return null;
        }
    }
}
