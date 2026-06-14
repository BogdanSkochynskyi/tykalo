package io.tykalo.menu.handler;

import io.tykalo.menu.ListSettingsService;
import io.tykalo.telegram.conversation.ConversationState;
import io.tykalo.telegram.conversation.StateHandler;
import io.tykalo.user.UserService;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;

/**
 * Routes the plain-text new name while the user is renaming a list (TK-186): in the
 * {@link ConversationState.RenamingList} state the message is handed to
 * {@link ListSettingsService#submitRename}, which validates it and either re-prompts in place or renames
 * the list and re-renders the settings screen. The dispatcher only consults this handler for non-command
 * text in an input-expecting state, so a {@code /command} still breaks out of the flow normally.
 */
@Component
@RequiredArgsConstructor
public class RenameListStateHandler implements StateHandler {

    private final UserService userService;
    private final ListSettingsService settingsService;

    @Override
    public boolean canHandle(final ConversationState state) {
        return state instanceof ConversationState.RenamingList;
    }

    @Override
    public Optional<String> handle(final Update update, final ConversationState state) {
        final Message message = update.getMessage();
        if (message == null || message.getText() == null) {
            return Optional.empty();
        }
        final ConversationState.RenamingList renaming = (ConversationState.RenamingList) state;
        return userService.find(update)
                .map(user -> settingsService.submitRename(user, renaming, message.getText()))
                .orElseGet(Optional::empty);
    }
}
