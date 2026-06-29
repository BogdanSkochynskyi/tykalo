package io.tykalo.menu.handler;

import io.tykalo.menu.NewListPendingService;
import io.tykalo.telegram.CallbackHandler;
import io.tykalo.telegram.conversation.ConversationState;
import io.tykalo.telegram.conversation.ConversationStateService;
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
 * Handles the pending-match screen buttons (TK-258), claiming the {@code clp:} {@code callback_data}
 * prefix: {@code clp:t:{itemId}} toggles a checkbox, {@code clp:add} restores the selected items into the
 * new list, {@code clp:drop} discards them, and {@code clp:skip} leaves them in the pending bucket. Each
 * edits the screen in place, so the clicking user is re-resolved from the chat and the message id taken
 * from the callback. The current selection lives in the user's
 * {@link ConversationState.CreatingListPendingCheck}; a tap after the flow has moved on is a harmless
 * "expired" toast. Non-{@code clp:} callbacks are left unclaimed.
 */
@Component
@RequiredArgsConstructor
public class NewListPendingCallbackHandler implements CallbackHandler {

    private static final String PREFIX = "clp:";
    private static final String EXPIRED = "This button has expired.";

    private final UserRepository userRepository;
    private final ConversationStateService conversationState;
    private final NewListPendingService newListPendingService;

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
        final Optional<User> user = userRepository.findByTgChatId(chatId);
        if (user.isEmpty()) {
            return Optional.of(EXPIRED);
        }
        if (!(conversationState.getState(user.get().getId())
                instanceof ConversationState.CreatingListPendingCheck state)) {
            return Optional.of(EXPIRED);
        }
        return route(user.get(), messageId, state, data);
    }

    private Optional<String> route(final User user, final int messageId,
                                   final ConversationState.CreatingListPendingCheck state, final String data) {
        if (data.equals(NewListPendingService.ADD)) {
            return newListPendingService.addSelected(user, messageId, state);
        }
        if (data.equals(NewListPendingService.DROP)) {
            return newListPendingService.dropSelected(user, messageId, state);
        }
        if (data.equals(NewListPendingService.SKIP)) {
            return newListPendingService.skip(user, messageId, state);
        }
        if (data.startsWith(NewListPendingService.TOGGLE_PREFIX)) {
            final UUID itemId = parseUuid(data.substring(NewListPendingService.TOGGLE_PREFIX.length()));
            if (itemId == null) {
                return Optional.of(EXPIRED);
            }
            return newListPendingService.toggle(user, messageId, state, itemId);
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
