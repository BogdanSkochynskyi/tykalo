package io.tykalo.list;

import io.tykalo.telegram.TelegramMessageGateway;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

/**
 * Publishes and refreshes the "live" Telegram message for a list. The first publish sends a new
 * message and records its id in {@code list_messages}; every later publish edits that same message
 * in place, so a chat keeps exactly one self-updating artifact per list.
 *
 * <p>The Telegram send/edit is intentionally not wrapped in a transaction — it is network I/O. The
 * repository reads and the single {@code save} are each transactional on their own.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ListMessageService {

    private final TaskRepository taskRepository;
    private final ListMessageRepository listMessageRepository;
    private final ListRenderer renderer;
    private final TelegramMessageGateway gateway;

    /**
     * Sends or refreshes the live message mirroring {@code list} in {@code chatId}. A new list gets
     * a fresh message; an existing one is edited in place. Safe to call after any change to the
     * list's tasks.
     */
    public void publish(final TaskList list, final long chatId) {
        final UUID listId = Objects.requireNonNull(list.getId(), "list must be persisted before publishing");
        final List<Task> tasks = taskRepository.findByListIdAndArchivedAtIsNullOrderByCreatedAtAsc(listId);
        final String text = compose(list, tasks);
        final InlineKeyboardMarkup keyboard = renderer.keyboard(tasks);

        final Optional<ListMessage> existing = listMessageRepository.findByListIdAndTgChatId(listId, chatId);
        if (existing.isPresent()) {
            edit(existing.get(), chatId, text, keyboard);
        } else {
            sendNew(listId, chatId, text, keyboard);
        }
    }

    private void edit(final ListMessage record, final long chatId, final String text,
                      final InlineKeyboardMarkup keyboard) {
        gateway.editMarkdown(chatId, (int) record.getTgMessageId(), text, keyboard);
        record.markRendered();
        listMessageRepository.save(record);
    }

    private void sendNew(final UUID listId, final long chatId, final String text,
                         final InlineKeyboardMarkup keyboard) {
        final Optional<Integer> messageId = gateway.sendMarkdown(chatId, text, keyboard);
        if (messageId.isEmpty()) {
            log.debug("No message id returned for list {} in chat {} — nothing persisted", listId, chatId);
            return;
        }
        listMessageRepository.save(ListMessage.of(listId, chatId, messageId.get()));
    }

    private String compose(final TaskList list, final List<Task> tasks) {
        return "*%s*\n\n%s".formatted(ListRenderer.escape(list.getName()), renderer.render(tasks));
    }
}
