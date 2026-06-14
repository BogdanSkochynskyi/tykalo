package io.tykalo.menu.handler;

import io.tykalo.menu.InviteMemberService;
import io.tykalo.telegram.conversation.ConversationState;
import io.tykalo.telegram.conversation.StateHandler;
import io.tykalo.user.UserService;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;

/**
 * Routes the typed {@code @username} while the user is in the {@link ConversationState.InvitingMember}
 * state (TK-193): the message is handed to {@link InviteMemberService#submitUsername}, which invites that
 * user and replies with a status line, staying in the state for further invites. The dispatcher only
 * consults this handler for non-command text in an input-expecting state, so a {@code /command} still
 * breaks out of the flow normally.
 */
@Component
@RequiredArgsConstructor
public class InviteMemberStateHandler implements StateHandler {

    private final UserService userService;
    private final InviteMemberService inviteMemberService;

    @Override
    public boolean canHandle(final ConversationState state) {
        return state instanceof ConversationState.InvitingMember;
    }

    @Override
    public Optional<String> handle(final Update update, final ConversationState state) {
        final Message message = update.getMessage();
        if (message == null || message.getText() == null) {
            return Optional.empty();
        }
        final ConversationState.InvitingMember inviting = (ConversationState.InvitingMember) state;
        return userService.find(update)
                .map(user -> inviteMemberService.submitUsername(user, inviting, message.getText()))
                .orElseGet(Optional::empty);
    }
}
