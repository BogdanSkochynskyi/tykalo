package io.tykalo.menu.handler;

import io.tykalo.menu.CreateListService;
import io.tykalo.telegram.conversation.ConversationState;
import io.tykalo.telegram.conversation.StateHandler;
import io.tykalo.user.UserService;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;

/**
 * Routes the plain-text tags input while the user is in the {@link ConversationState.CreatingListTags}
 * state (TK-258 step 3): the message is handed to {@link CreateListService#submitTags}, which validates
 * the tags and either re-prompts in place or creates the list and runs the pending-match check. The
 * dispatcher only consults this handler for non-command text in an input-expecting state, so a
 * {@code /command} still breaks out of the flow normally.
 */
@Component
@RequiredArgsConstructor
public class CreateListTagsStateHandler implements StateHandler {

    private final UserService userService;
    private final CreateListService createListService;

    @Override
    public boolean canHandle(final ConversationState state) {
        return state instanceof ConversationState.CreatingListTags;
    }

    @Override
    public Optional<String> handle(final Update update, final ConversationState state) {
        final Message message = update.getMessage();
        if (message == null || message.getText() == null) {
            return Optional.empty();
        }
        final ConversationState.CreatingListTags tags = (ConversationState.CreatingListTags) state;
        return userService.find(update)
                .map(user -> createListService.submitTags(user, tags, message.getText()))
                .orElseGet(Optional::empty);
    }
}
