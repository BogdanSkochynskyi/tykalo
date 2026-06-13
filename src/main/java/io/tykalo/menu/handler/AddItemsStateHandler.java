package io.tykalo.menu.handler;

import io.tykalo.menu.AddItemsService;
import io.tykalo.telegram.conversation.ConversationState;
import io.tykalo.telegram.conversation.StateHandler;
import io.tykalo.user.UserService;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;

/**
 * Routes plain-text messages while the user is adding items (TK-184): in the
 * {@link ConversationState.AddingItems} state each message becomes one or more tasks via
 * {@link AddItemsService#addItems}. The dispatcher only consults this handler for non-command text in an
 * input-expecting state, so a {@code /command} still breaks out of the flow normally.
 */
@Component
@RequiredArgsConstructor
public class AddItemsStateHandler implements StateHandler {

    private final UserService userService;
    private final AddItemsService addItemsService;

    @Override
    public boolean canHandle(final ConversationState state) {
        return state instanceof ConversationState.AddingItems;
    }

    @Override
    public Optional<String> handle(final Update update, final ConversationState state) {
        final Message message = update.getMessage();
        if (message == null || message.getText() == null) {
            return Optional.empty();
        }
        final ConversationState.AddingItems adding = (ConversationState.AddingItems) state;
        return userService.find(update)
                .map(user -> addItemsService.addItems(user, adding, message.getText()))
                .orElseGet(Optional::empty);
    }
}
