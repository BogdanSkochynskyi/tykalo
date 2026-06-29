package io.tykalo.menu.handler;

import io.tykalo.menu.TagsService;
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
 * Handles the tags-screen buttons (TK-259), claiming the {@code tg:} {@code callback_data} prefix:
 * {@code tg:open} renders the tags screen, {@code tg:add} opens the add-tag prompt, {@code tg:q:{i}}
 * quick-adds the i-th suggestion, {@code tg:rm:{i}} asks to confirm removing the i-th tag,
 * {@code tg:rmok:{i}} performs that removal, and {@code tg:back} returns to the settings screen. The
 * clicking user is re-resolved from the chat and the message id taken from the callback. Non-{@code tg:}
 * callbacks are left unclaimed.
 */
@Component
@RequiredArgsConstructor
public class TagsCallbackHandler implements CallbackHandler {

    private static final String EXPIRED = "This button has expired.";
    private static final String GONE = "That list is no longer available or you can't edit it.";

    private final UserRepository userRepository;
    private final TagsService tagsService;

    @Override
    public Optional<String> handle(final CallbackQuery callback) {
        final String data = callback.getData();
        if (data == null || !data.startsWith("tg:")) {
            return Optional.empty();
        }
        final Long chatId = chatIdOf(callback);
        final Integer messageId = messageIdOf(callback);
        if (chatId == null || messageId == null) {
            return Optional.of(EXPIRED);
        }
        final Optional<User> user = userRepository.findByTgChatId(chatId);
        if (user.isEmpty()) {
            return Optional.of(EXPIRED);
        }
        return route(data, user.get(), messageId);
    }

    private Optional<String> route(final String data, final User user, final int messageId) {
        if (data.startsWith(TagsService.OPEN_PREFIX)) {
            return listScoped(data, TagsService.OPEN_PREFIX,
                    listId -> tagsService.open(user, messageId, listId), "🏷️ Tags");
        }
        if (data.startsWith(TagsService.ADD_PREFIX)) {
            return listScoped(data, TagsService.ADD_PREFIX,
                    listId -> tagsService.promptAdd(user, messageId, listId), "✏️ Add a tag");
        }
        if (data.startsWith(TagsService.BACK_PREFIX)) {
            return listScoped(data, TagsService.BACK_PREFIX,
                    listId -> tagsService.back(user, messageId, listId), "⚙️ Settings");
        }
        if (data.startsWith(TagsService.QUICK_PREFIX)) {
            return indexed(data, TagsService.QUICK_PREFIX,
                    (listId, index) -> tagsService.quickAdd(user, messageId, listId, index));
        }
        if (data.startsWith(TagsService.REMOVE_CONFIRM_PREFIX)) {
            return indexed(data, TagsService.REMOVE_CONFIRM_PREFIX,
                    (listId, index) -> tagsService.remove(user, messageId, listId, index));
        }
        if (data.startsWith(TagsService.REMOVE_PREFIX)) {
            return indexed(data, TagsService.REMOVE_PREFIX,
                    (listId, index) -> tagsService.confirmRemove(user, messageId, listId, index)
                            .map(name -> "🗑️ Confirm removal"));
        }
        return Optional.empty();
    }

    private Optional<String> listScoped(final String data, final String prefix,
                                        final Function<UUID, Optional<String>> action,
                                        final String toast) {
        final UUID listId = parseUuid(data.substring(prefix.length()));
        if (listId == null) {
            return Optional.of(EXPIRED);
        }
        return action.apply(listId).map(ignored -> toast).or(() -> Optional.of(GONE));
    }

    private Optional<String> indexed(final String data, final String prefix, final IndexedAction action) {
        final String[] parts = data.substring(prefix.length()).split(":", 2);
        if (parts.length < 2) {
            return Optional.of(EXPIRED);
        }
        final UUID listId = parseUuid(parts[0]);
        final Integer index = parseInt(parts[1]);
        if (listId == null || index == null) {
            return Optional.of(EXPIRED);
        }
        return action.apply(listId, index).or(() -> Optional.of(GONE));
    }

    @FunctionalInterface
    private interface IndexedAction {
        Optional<String> apply(UUID listId, int index);
    }

    private @Nullable UUID parseUuid(final String raw) {
        try {
            return UUID.fromString(raw);
        } catch (final IllegalArgumentException e) {
            return null;
        }
    }

    private @Nullable Integer parseInt(final String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (final NumberFormatException e) {
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
