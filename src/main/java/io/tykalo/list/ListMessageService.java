package io.tykalo.list;

import io.tykalo.telegram.TelegramMessageGateway;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

/**
 * Publishes and refreshes the "live" Telegram message(s) for a list. The first publish in a chat
 * sends a new message and records its id in {@code list_messages}; every later refresh edits that
 * same message in place, so a chat keeps exactly one self-updating artifact per list.
 *
 * <p>Refresh is driven by {@link ListChangedEvent}: {@link TaskService} fires it after every task
 * mutation and {@link #onListChanged} re-renders all of the list's live messages. This is why no
 * command/callback handler needs to remember to refresh — adding, toggling, editing, snoozing or
 * archiving a task all flow through the same event. The listener runs {@code AFTER_COMMIT} so the
 * change is visible and the Telegram I/O happens outside the database transaction.
 *
 * <p>The Telegram send/edit is intentionally not wrapped in a transaction — it is network I/O. The
 * repository reads and the single {@code save} are each transactional on their own.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ListMessageService {

    private final TaskRepository taskRepository;
    private final ListRepository listRepository;
    private final ListMessageRepository listMessageRepository;
    private final ListRenderer renderer;
    private final TelegramMessageGateway gateway;

    /**
     * Re-renders every live message mirroring the changed list, editing each in place. Edit-only: it
     * never sends a new message, so a mutation of a list nobody is viewing produces no chatter. Runs
     * after the mutating transaction commits, off the database connection.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onListChanged(final ListChangedEvent event) {
        refresh(event.listId());
    }

    /**
     * Sends or refreshes the live message mirroring {@code list} in {@code chatId}. A chat without one
     * yet gets a fresh message; an existing one is edited in place.
     */
    public void publish(final TaskList list, final long chatId) {
        final UUID listId = requireId(list);
        final Rendered rendered = render(list);
        final Optional<ListMessage> existing = listMessageRepository.findByListIdAndTgChatId(listId, chatId);
        if (existing.isPresent()) {
            edit(existing.get(), chatId, rendered);
        } else {
            sendNew(listId, chatId, rendered);
        }
    }

    /**
     * Sends the live message for {@code list} in {@code chatId} only if none exists there yet; a no-op
     * when one already does (its refresh is handled by {@link #onListChanged}). This lets a quick-capture
     * flow guarantee the message exists without double-rendering against the change event.
     */
    public void publishIfAbsent(final TaskList list, final long chatId) {
        final UUID listId = requireId(list);
        if (listMessageRepository.findByListIdAndTgChatId(listId, chatId).isPresent()) {
            return;
        }
        sendNew(listId, chatId, render(list));
    }

    private void refresh(final UUID listId) {
        final List<ListMessage> liveMessages = listMessageRepository.findByListId(listId);
        if (liveMessages.isEmpty()) {
            return;
        }
        final Optional<TaskList> list = listRepository.findById(listId);
        if (list.isEmpty()) {
            log.warn("List {} not found while refreshing its live messages", listId);
            return;
        }
        final Rendered rendered = render(list.get());
        liveMessages.forEach(message -> edit(message, message.getTgChatId(), rendered));
    }

    private void edit(final ListMessage record, final long chatId, final Rendered rendered) {
        gateway.editMarkdown(chatId, (int) record.getTgMessageId(), rendered.text(), rendered.keyboard());
        record.markRendered();
        listMessageRepository.save(record);
    }

    private void sendNew(final UUID listId, final long chatId, final Rendered rendered) {
        final Optional<Integer> messageId = gateway.sendMarkdownDirect(chatId, rendered.text(), rendered.keyboard());
        if (messageId.isEmpty()) {
            log.debug("No message id returned for list {} in chat {} — nothing persisted", listId, chatId);
            return;
        }
        listMessageRepository.save(ListMessage.of(listId, chatId, messageId.get()));
    }

    private Rendered render(final TaskList list) {
        final List<Task> tasks =
                taskRepository.findByListIdAndArchivedAtIsNullOrderByCreatedAtAsc(requireId(list));
        final String text = "*%s*\n\n%s".formatted(ListRenderer.escape(list.getName()), renderer.render(tasks));
        return new Rendered(text, renderer.keyboard(tasks));
    }

    private UUID requireId(final TaskList list) {
        return Objects.requireNonNull(list.getId(), "list must be persisted before publishing");
    }

    private record Rendered(String text, InlineKeyboardMarkup keyboard) {
    }
}
