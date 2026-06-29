package io.tykalo.menu.handler;

import io.tykalo.menu.ListSettingsService;
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
 * Handles the per-list settings screen buttons (TK-253), claiming the {@code ls:} {@code callback_data}
 * prefix: {@code ls:ac:{list}} flips the auto-close setting and re-renders the screen, and
 * {@code ls:back:{list}} returns to the list view. The clicking user is re-resolved from the chat and the
 * message id taken from the callback. Non-{@code ls:} callbacks are left unclaimed.
 */
@Component
@RequiredArgsConstructor
public class ListSettingsCallbackHandler implements CallbackHandler {

    private static final String EXPIRED = "This button has expired.";
    private static final String GONE = "That list is no longer available or you can't edit it.";

    private final UserRepository userRepository;
    private final ListSettingsService listSettingsService;

    @Override
    public Optional<String> handle(final CallbackQuery callback) {
        final String data = callback.getData();
        if (data == null || !data.startsWith("ls:")) {
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
        final UUID listId = parseUuid(data.substring(data.lastIndexOf(':') + 1));
        if (listId == null) {
            return Optional.of(EXPIRED);
        }
        if (data.startsWith(ListSettingsService.TOGGLE_AUTO_CLOSE_PREFIX)) {
            return Optional.of(listSettingsService.toggleAutoClose(user.get(), messageId, listId).orElse(GONE));
        }
        if (data.startsWith(ListSettingsService.BACK_PREFIX)) {
            return listSettingsService.back(user.get(), messageId, listId).isPresent()
                    ? Optional.of("⬅️ Back")
                    : Optional.of(GONE);
        }
        return Optional.empty();
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
