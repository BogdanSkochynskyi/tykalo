package io.tykalo.menu;

import io.tykalo.list.ListInvitePromptService;
import io.tykalo.list.ListInviteResult;
import io.tykalo.list.ListInviteService;
import io.tykalo.list.ListMemberRole;
import io.tykalo.list.ListPermissionService;
import io.tykalo.list.ListRenderer;
import io.tykalo.list.ListService;
import io.tykalo.list.TaskList;
import io.tykalo.telegram.TelegramMessageGateway;
import io.tykalo.telegram.conversation.ConversationState;
import io.tykalo.telegram.conversation.ConversationStateService;
import io.tykalo.user.User;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

/**
 * The invite-by-username sub-flow, launched from the Members screen (TK-194; originally the interim
 * TK-193 entry point). {@link #start} edits the screen message in place into an invite prompt and sets
 * the user's {@link ConversationState} to {@link ConversationState.InvitingMember}. Each plain-text
 * message in that state is one {@code @username} to invite (default role MEMBER) via
 * {@link ListInviteService}; the registered invitee gets the Yes/No prompt
 * ({@link ListInvitePromptService}). {@code ❌ Cancel} ({@link #CANCEL_PREFIX}) returns to the Members
 * screen. The flow is gated by {@link ListPermissionService#canManageMembers} so only OWNER/EDITOR can
 * open it.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InviteMemberService {

    /** {@code ❌ Cancel} callback on the invite prompt — routed back to the Members screen (TK-194). */
    public static final String CANCEL_PREFIX = "ms:invx:";

    private static final ListMemberRole DEFAULT_ROLE = ListMemberRole.MEMBER;

    private final ListService listService;
    private final ListInviteService inviteService;
    private final ListInvitePromptService promptService;
    private final ListPermissionService permissionService;
    private final ConversationStateService conversationState;
    private final TelegramMessageGateway gateway;

    /**
     * Opens the invite prompt for {@code listId} in place of the list-view message {@code messageId}.
     * Returns the list name (for the opener's toast), or empty if the list is gone or the user may not
     * manage its members.
     */
    public Optional<String> start(final User user, final int messageId, final UUID listId) {
        if (!permissionService.canManageMembers(user.getId(), listId)) {
            return Optional.empty();
        }
        final Optional<TaskList> list = listService.getActiveById(listId);
        if (list.isEmpty()) {
            return Optional.empty();
        }
        final String link = inviteService.createShareLink(user, listId, DEFAULT_ROLE);
        conversationState.setState(user.getId(), new ConversationState.InvitingMember(listId, DEFAULT_ROLE, messageId));
        gateway.editMarkdown(user.getTgChatId(), messageId, ListRenderer.escape(promptText(list.get(), link)),
                cancelKeyboard(listId));
        log.debug("Opened invite prompt for list={} by user id={}", listId, user.getId());
        return Optional.of(list.get().getName());
    }

    /**
     * Consumes one typed {@code @username} while inviting: invites them as the state's role and, for a
     * registered invitee, sends the Yes/No prompt. Returns the plain-text status to reply with; stays in
     * the {@code InvitingMember} state so several people can be invited in a row.
     */
    public Optional<String> submitUsername(final User user, final ConversationState.InvitingMember state,
            final String rawUsername) {
        final String username = rawUsername == null ? "" : rawUsername.strip();
        if (username.isBlank()) {
            return Optional.of("Send a @username to invite, or tap ❌ Cancel.");
        }
        final ListInviteResult result = inviteService.inviteByUsername(user, state.listId(), state.role(), username);
        final String status = switch (result) {
            case ListInviteResult.Invited invited -> {
                promptService.sendInvitePrompt(invited.member(), invited.invitee(), user, invited.list());
                yield "✅ Invited @%s — waiting for them to accept."
                        .formatted(invited.invitee().getTgUsername());
            }
            case ListInviteResult.AlreadyPending pending -> "@%s already has a pending invite to this list."
                    .formatted(pending.invitee().getTgUsername());
            case ListInviteResult.AlreadyMember member -> "@%s is already a member of this list."
                    .formatted(member.invitee().getTgUsername());
            case ListInviteResult.SelfInvite ignored -> "You can't invite yourself 🙂";
            case ListInviteResult.NotRegistered notRegistered -> ("@%s isn't on Tykalo yet — share the link "
                    + "above so they can join.").formatted(notRegistered.username());
        };
        return Optional.of(status);
    }

    private String promptText(final TaskList list, final String link) {
        return ("👥 Invite to \"%s\"\n\nSend a @username to invite them as a member, or share this link:\n%s"
                + "\n\nTap ❌ Cancel when you're done.").formatted(list.getName(), link);
    }

    private InlineKeyboardMarkup cancelKeyboard(final UUID listId) {
        final InlineKeyboardRow row = new InlineKeyboardRow();
        row.add(InlineKeyboardButton.builder().text("❌ Cancel").callbackData(CANCEL_PREFIX + listId).build());
        return InlineKeyboardMarkup.builder().keyboardRow(row).build();
    }
}
