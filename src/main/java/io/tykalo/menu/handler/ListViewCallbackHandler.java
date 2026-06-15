package io.tykalo.menu.handler;

import io.tykalo.list.Task;
import io.tykalo.list.TaskService;
import io.tykalo.menu.AddItemsService;
import io.tykalo.menu.ListViewService;
import io.tykalo.menu.MembersService;
import io.tykalo.menu.MyListsService;
import io.tykalo.telegram.CallbackHandler;
import io.tykalo.user.User;
import io.tykalo.user.UserRepository;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.message.MaybeInaccessibleMessage;

/**
 * Handles the list-view buttons (TK-183), claiming the {@code lv:} {@code callback_data} prefix:
 * {@code lv:done|undo:{task}:{page}} toggles an item and re-renders the same page in place,
 * {@code lv:page:{list}:{n}} pages, {@code lv:lists} returns to the My Lists screen, {@code lv:add:{list}}
 * starts the add-items flow (TK-184), {@code lv:members:{list}} opens the Members screen (TK-194),
 * while {@code lv:more:{list}} is a placeholder until the more menu (TK-186) lands. Everything that edits the screen re-resolves the
 * clicking user from the chat and takes the message id from the callback. Non-{@code lv:} callbacks
 * are left unclaimed.
 */
@Component
@RequiredArgsConstructor
public class ListViewCallbackHandler implements CallbackHandler {

    private static final String EXPIRED = "This button has expired.";

    private final UserRepository userRepository;
    private final TaskService taskService;
    private final ListViewService listViewService;
    private final MyListsService myListsService;
    private final AddItemsService addItemsService;
    private final MembersService membersService;

    @Override
    public Optional<String> handle(final CallbackQuery callback) {
        final String data = callback.getData();
        if (data == null || !data.startsWith("lv:")) {
            return Optional.empty();
        }
        if (data.startsWith(ListViewService.MORE_PREFIX)) {
            return Optional.of("⋯ More options are coming soon (TK-186).");
        }
        // The remaining actions edit the screen in place, so they need the user and message id.
        final Long chatId = chatIdOf(callback);
        final Integer messageId = messageIdOf(callback);
        if (chatId == null || messageId == null) {
            return Optional.of(EXPIRED);
        }
        final Optional<User> user = userRepository.findByTgChatId(chatId);
        if (user.isEmpty()) {
            return Optional.of(EXPIRED);
        }
        if (data.startsWith(ListViewService.MEMBERS_PREFIX)) {
            return openMembers(data.substring(ListViewService.MEMBERS_PREFIX.length()), user.get(), messageId);
        }
        if (data.equals(ListViewService.BACK)) {
            myListsService.navigate(user.get(), messageId, 0);
            return Optional.of("📋 My Lists");
        }
        if (data.startsWith(ListViewService.ADD_PREFIX)) {
            final UUID listId = parseUuid(data.substring(ListViewService.ADD_PREFIX.length()));
            if (listId == null) {
                return Optional.of(EXPIRED);
            }
            return addItemsService.start(user.get(), messageId, listId)
                    .map("➕ Adding items to "::concat)
                    .or(() -> Optional.of("That list is no longer available."));
        }
        if (data.startsWith(ListViewService.DONE_PREFIX)) {
            return toggle(data.substring(ListViewService.DONE_PREFIX.length()), user.get(), messageId, true);
        }
        if (data.startsWith(ListViewService.UNDO_PREFIX)) {
            return toggle(data.substring(ListViewService.UNDO_PREFIX.length()), user.get(), messageId, false);
        }
        if (data.startsWith(ListViewService.PAGE_PREFIX)) {
            return paginate(data.substring(ListViewService.PAGE_PREFIX.length()), user.get(), messageId);
        }
        return Optional.empty();
    }

    private Optional<String> openMembers(final String rawListId, final User user, final int messageId) {
        final UUID listId = parseUuid(rawListId);
        if (listId == null) {
            return Optional.of(EXPIRED);
        }
        return membersService.open(user, messageId, listId)
                .map(ignored -> "👥 Members")
                .or(() -> Optional.of("That list is no longer available."));
    }

    private Optional<String> toggle(final String rest, final User user, final int messageId, final boolean done) {
        final String[] parts = rest.split(":", 2);
        if (parts.length < 2) {
            return Optional.of(EXPIRED);
        }
        final UUID taskId = parseUuid(parts[0]);
        final Optional<Integer> page = parseInt(parts[1]);
        if (taskId == null || page.isEmpty()) {
            return Optional.of(EXPIRED);
        }
        final Optional<Task> task = taskService.find(taskId);
        if (task.isEmpty() || task.get().getArchivedAt() != null) {
            return Optional.of("Task not found.");
        }
        if (done) {
            taskService.markDone(user.getId(), taskId);
        } else {
            taskService.reopen(user.getId(), taskId);
        }
        final Optional<String> shown = listViewService.show(user, messageId, task.get().getListId(), page.get());
        return shown.isPresent() ? Optional.of(done ? "✅ Done" : "↩️ Reopened")
                : Optional.of("That list is no longer available.");
    }

    private Optional<String> paginate(final String rest, final User user, final int messageId) {
        final String[] parts = rest.split(":", 2);
        if (parts.length < 2) {
            return Optional.of(EXPIRED);
        }
        final UUID listId = parseUuid(parts[0]);
        final Optional<Integer> page = parseInt(parts[1]);
        if (listId == null || page.isEmpty()) {
            return Optional.of(EXPIRED);
        }
        final Optional<String> shown = listViewService.show(user, messageId, listId, page.get());
        return shown.isPresent() ? Optional.of("Page " + (page.get() + 1))
                : Optional.of("That list is no longer available.");
    }

    private @Nullable UUID parseUuid(final String raw) {
        try {
            return UUID.fromString(raw);
        } catch (final IllegalArgumentException e) {
            return null;
        }
    }

    private Optional<Integer> parseInt(final String raw) {
        try {
            return Optional.of(Integer.parseInt(raw));
        } catch (final NumberFormatException e) {
            return Optional.empty();
        }
    }

    private @Nullable Long chatIdOf(final CallbackQuery callback) {
        final MaybeInaccessibleMessage message = callback.getMessage();
        return message == null ? null : message.getChatId();
    }

    private @Nullable Integer messageIdOf(final CallbackQuery callback) {
        final MaybeInaccessibleMessage message = callback.getMessage();
        return message == null ? null : message.getMessageId();
    }
}
