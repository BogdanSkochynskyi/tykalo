package io.tykalo.menu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.STRING;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.tykalo.list.ListInvitePromptService;
import io.tykalo.list.ListInviteResult;
import io.tykalo.list.ListInviteService;
import io.tykalo.list.ListMember;
import io.tykalo.list.ListMemberRole;
import io.tykalo.list.ListPermissionService;
import io.tykalo.list.ListService;
import io.tykalo.list.ListType;
import io.tykalo.list.TaskList;
import io.tykalo.telegram.TelegramMessageGateway;
import io.tykalo.telegram.conversation.ConversationState;
import io.tykalo.telegram.conversation.ConversationStateService;
import io.tykalo.user.User;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InviteMemberServiceTest {

    @Mock
    private ListService listService;
    @Mock
    private ListInviteService inviteService;
    @Mock
    private ListInvitePromptService promptService;
    @Mock
    private ListPermissionService permissionService;
    @Mock
    private ConversationStateService conversationState;
    @Mock
    private TelegramMessageGateway gateway;

    @InjectMocks
    private InviteMemberService service;

    @Test
    void start_opensPromptAndSetsState_forAManager() {
        final User user = user("alice");
        final TaskList list = list(user, "Groceries");
        when(permissionService.canManageMembers(user.getId(), list.getId())).thenReturn(true);
        when(listService.getActiveById(list.getId())).thenReturn(Optional.of(list));
        when(inviteService.createShareLink(user, list.getId(), ListMemberRole.MEMBER))
                .thenReturn("https://t.me/TykaloBot?start=list_invite_x");

        final Optional<String> name = service.start(user, 42, list.getId());

        assertThat(name).contains("Groceries");
        verify(conversationState).setState(eq(user.getId()),
                eq(new ConversationState.InvitingMember(list.getId(), ListMemberRole.MEMBER, 42)));
        verify(gateway).editMarkdown(eq(user.getTgChatId()), eq(42), any(), any());
    }

    @Test
    void start_isRefused_whenUserCannotManageMembers() {
        final User user = user("bob");
        final UUID listId = UUID.randomUUID();
        when(permissionService.canManageMembers(user.getId(), listId)).thenReturn(false);

        final Optional<String> name = service.start(user, 42, listId);

        assertThat(name).isEmpty();
        verifyNoInteractions(inviteService, gateway);
        verify(conversationState, never()).setState(any(), any());
    }

    @Test
    void submitUsername_invited_sendsPromptAndReportsWaiting() {
        final User user = user("alice");
        final User invitee = user("bob");
        final TaskList list = list(user, "Groceries");
        final ListMember member = ListMember.pendingInvite(
                list.getId(), invitee.getId(), ListMemberRole.MEMBER, user.getId());
        final ConversationState.InvitingMember state =
                new ConversationState.InvitingMember(list.getId(), ListMemberRole.MEMBER, 42);
        when(inviteService.inviteByUsername(user, list.getId(), ListMemberRole.MEMBER, "@bob"))
                .thenReturn(new ListInviteResult.Invited(member, invitee, list));

        final Optional<String> status = service.submitUsername(user, state, "@bob");

        assertThat(status).get(STRING).contains("Invited @bob");
        verify(promptService).sendInvitePrompt(member, invitee, user, list);
    }

    @Test
    void submitUsername_notRegistered_pointsToTheShareLink() {
        final User user = user("alice");
        final ConversationState.InvitingMember state =
                new ConversationState.InvitingMember(UUID.randomUUID(), ListMemberRole.MEMBER, 42);
        when(inviteService.inviteByUsername(eq(user), any(), eq(ListMemberRole.MEMBER), eq("ghost")))
                .thenReturn(new ListInviteResult.NotRegistered("ghost"));

        final Optional<String> status = service.submitUsername(user, state, "ghost");

        assertThat(status).get(STRING).contains("isn't on Tykalo");
        verify(promptService, never()).sendInvitePrompt(any(), any(), any(), any());
    }

    private User user(final String username) {
        final User user = User.create((long) username.hashCode(), username, ZoneId.of("Europe/Kyiv"), "uk");
        user.setId(UUID.randomUUID());
        return user;
    }

    private TaskList list(final User owner, final String name) {
        final TaskList list = TaskList.of(owner, name, ListType.CHECKLIST);
        list.setId(UUID.randomUUID());
        return list;
    }
}
