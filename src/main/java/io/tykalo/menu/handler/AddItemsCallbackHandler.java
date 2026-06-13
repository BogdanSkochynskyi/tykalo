package io.tykalo.menu.handler;

import io.tykalo.menu.AddItemsService;
import io.tykalo.telegram.CallbackHandler;
import io.tykalo.telegram.TelegramMessageGateway;
import io.tykalo.telegram.conversation.ConversationState;
import io.tykalo.telegram.conversation.ConversationStateService;
import io.tykalo.user.User;
import io.tykalo.user.UserRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.message.MaybeInaccessibleMessage;

/**
 * Handles the add-items prompt buttons (TK-184), claiming the {@code add:} {@code callback_data} prefix:
 * {@code add:done} and {@code add:cancel} both end the flow via {@link AddItemsService#finish} — deleting
 * the prompt and returning to the list view — keeping the items already added. The clicking user is
 * re-resolved from the chat and the prompt message id taken from the callback. If the flow already ended
 * (state is no longer {@link ConversationState.AddingItems}) the stale prompt is simply removed.
 */
@Component
@RequiredArgsConstructor
public class AddItemsCallbackHandler implements CallbackHandler {

    private static final String PREFIX = "add:";
    private static final String EXPIRED = "This button has expired.";

    private final UserRepository userRepository;
    private final ConversationStateService conversationState;
    private final AddItemsService addItemsService;
    private final TelegramMessageGateway gateway;

    @Override
    public Optional<String> handle(final CallbackQuery callback) {
        final String data = callback.getData();
        if (data == null || !data.startsWith(PREFIX)) {
            return Optional.empty();
        }
        final boolean done = data.equals(AddItemsService.DONE);
        final boolean cancel = data.equals(AddItemsService.CANCEL);
        if (!done && !cancel) {
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
        final ConversationState state = conversationState.getState(user.get().getId());
        if (state instanceof ConversationState.AddingItems adding) {
            return addItemsService.finish(user.get(), adding, messageId, done);
        }
        // The flow already ended — just clean up the stale prompt.
        gateway.deleteMessage(chatId, messageId);
        return Optional.of("This add session has ended.");
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
