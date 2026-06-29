package io.tykalo.menu.handler;

import io.tykalo.menu.AutoCloseService;
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
 * Handles the auto-close prompt buttons (TK-253), claiming the {@code ac:} {@code callback_data} prefix:
 * {@code ac:close:{list}} closes an all-done list (reusing the TK-254 confirmation, which re-renders the
 * prompt into the read-only list view), and {@code ac:keep:{list}} keeps it open and suppresses the prompt
 * for an hour. The clicking user is re-resolved from the chat and the message id taken from the callback.
 * Non-{@code ac:} callbacks are left unclaimed.
 */
@Component
@RequiredArgsConstructor
public class AutoCloseCallbackHandler implements CallbackHandler {

    private static final String EXPIRED = "This button has expired.";
    private static final String GONE = "That list is no longer available.";

    private final UserRepository userRepository;
    private final AutoCloseService autoCloseService;

    @Override
    public Optional<String> handle(final CallbackQuery callback) {
        final String data = callback.getData();
        if (data == null || !data.startsWith("ac:")) {
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
        if (data.startsWith(AutoCloseService.CLOSE_PREFIX)) {
            return Optional.of(autoCloseService.confirmClose(user.get(), messageId, listId).orElse(GONE));
        }
        if (data.startsWith(AutoCloseService.KEEP_PREFIX)) {
            return Optional.of(autoCloseService.keepOpen(user.get(), messageId, listId).orElse(GONE));
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
