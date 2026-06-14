package io.tykalo.menu.handler;

import io.tykalo.list.ListType;
import io.tykalo.menu.ListSettingsService;
import io.tykalo.menu.ListViewService;
import io.tykalo.telegram.CallbackHandler;
import io.tykalo.user.User;
import io.tykalo.user.UserRepository;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.message.MaybeInaccessibleMessage;

/**
 * Handles the list-settings buttons (TK-186), claiming the {@code set:} {@code callback_data} prefix:
 * {@code set:rename} / {@code set:type} / {@code set:settype:{list}:{TYPE}} / {@code set:archive} /
 * {@code set:delete} / {@code set:delok} navigate the settings flow (see {@link ListSettingsService}),
 * {@code set:menu} re-opens the settings screen (the cancel/back target for sub-screens) and
 * {@code set:back} returns to the list view (TK-183). Every action edits the screen in place, so the
 * clicking user is re-resolved from the chat and the message id taken from the callback. Non-{@code set:}
 * callbacks are left unclaimed.
 */
@Component
@RequiredArgsConstructor
public class ListSettingsCallbackHandler implements CallbackHandler {

    private static final String PREFIX = "set:";
    private static final String EXPIRED = "This button has expired.";
    private static final String LIST_GONE = "That list is no longer available.";

    private final UserRepository userRepository;
    private final ListSettingsService settingsService;
    private final ListViewService listViewService;

    @Override
    public Optional<String> handle(final CallbackQuery callback) {
        final String data = callback.getData();
        if (data == null || !data.startsWith(PREFIX)) {
            return Optional.empty();
        }
        final Long chatId = chatIdOf(callback);
        final Integer messageId = messageIdOf(callback);
        if (chatId == null || messageId == null) {
            return Optional.of(EXPIRED);
        }
        final Optional<User> found = userRepository.findByTgChatId(chatId);
        if (found.isEmpty()) {
            return Optional.of(EXPIRED);
        }
        final User user = found.get();
        if (data.startsWith(ListSettingsService.SET_TYPE_PREFIX)) {
            return applyType(user, messageId, data.substring(ListSettingsService.SET_TYPE_PREFIX.length()));
        }
        return routeSingleId(user, messageId, data);
    }

    private Optional<String> routeSingleId(final User user, final int messageId, final String data) {
        if (data.startsWith(ListSettingsService.RENAME_PREFIX)) {
            return withListId(data, ListSettingsService.RENAME_PREFIX,
                    listId -> settingsService.startRename(user, messageId, listId));
        }
        if (data.startsWith(ListSettingsService.TYPE_PREFIX)) {
            return withListId(data, ListSettingsService.TYPE_PREFIX,
                    listId -> settingsService.showTypePicker(user, messageId, listId));
        }
        if (data.startsWith(ListSettingsService.ARCHIVE_PREFIX)) {
            return withListId(data, ListSettingsService.ARCHIVE_PREFIX,
                    listId -> settingsService.archive(user, messageId, listId));
        }
        if (data.startsWith(ListSettingsService.DELETE_OK_PREFIX)) {
            return withListId(data, ListSettingsService.DELETE_OK_PREFIX,
                    listId -> settingsService.delete(user, messageId, listId));
        }
        if (data.startsWith(ListSettingsService.DELETE_PREFIX)) {
            return withListId(data, ListSettingsService.DELETE_PREFIX,
                    listId -> settingsService.confirmDelete(user, messageId, listId));
        }
        if (data.startsWith(ListSettingsService.MENU_PREFIX)) {
            return withListId(data, ListSettingsService.MENU_PREFIX,
                    listId -> settingsService.open(user, messageId, listId)
                            .map("⚙️ "::concat)
                            .or(() -> Optional.of(LIST_GONE)));
        }
        if (data.startsWith(ListSettingsService.BACK_PREFIX)) {
            return withListId(data, ListSettingsService.BACK_PREFIX,
                    listId -> listViewService.open(user, messageId, listId)
                            .map("📋 "::concat)
                            .or(() -> Optional.of(LIST_GONE)));
        }
        return Optional.empty();
    }

    private Optional<String> applyType(final User user, final int messageId, final String rest) {
        final String[] parts = rest.split(":", 2);
        if (parts.length < 2) {
            return Optional.of(EXPIRED);
        }
        final UUID listId = parseUuid(parts[0]);
        final ListType type = parseType(parts[1]);
        if (listId == null || type == null) {
            return Optional.of(EXPIRED);
        }
        return settingsService.changeType(user, messageId, listId, type);
    }

    private Optional<String> withListId(final String data, final String prefix,
                                        final Function<UUID, Optional<String>> action) {
        final UUID listId = parseUuid(data.substring(prefix.length()));
        if (listId == null) {
            return Optional.of(EXPIRED);
        }
        return action.apply(listId);
    }

    private @Nullable ListType parseType(final String raw) {
        try {
            final ListType type = ListType.valueOf(raw);
            return type == ListType.INBOX ? null : type;
        } catch (final IllegalArgumentException e) {
            return null;
        }
    }

    private @Nullable UUID parseUuid(final String raw) {
        try {
            return UUID.fromString(raw);
        } catch (final IllegalArgumentException e) {
            return null;
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
