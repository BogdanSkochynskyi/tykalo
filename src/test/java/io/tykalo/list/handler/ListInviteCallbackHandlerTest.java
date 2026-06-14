package io.tykalo.list.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.STRING;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.tykalo.list.InviteResponseResult;
import io.tykalo.list.ListInviteService;
import io.tykalo.list.ListMember;
import io.tykalo.list.ListMemberRole;
import io.tykalo.list.ListType;
import io.tykalo.list.TaskList;
import io.tykalo.telegram.TelegramMessageGateway;
import io.tykalo.user.User;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;

@ExtendWith(MockitoExtension.class)
class ListInviteCallbackHandlerTest {

    private static final long INVITER_CHAT_ID = 7L;

    @Mock
    private ListInviteService inviteService;
    @Mock
    private TelegramMessageGateway gateway;

    @InjectMocks
    private ListInviteCallbackHandler handler;

    @Test
    void accept_flipsMembership_notifiesInviter_andConfirmsToInvitee() {
        final UUID memberId = UUID.randomUUID();
        when(inviteService.respond(memberId, true))
                .thenReturn(new InviteResponseResult.Accepted(member(), list("Groceries"), inviter()));

        final Optional<String> toast = handler.handle(callback("lm:accept:" + memberId, "bob"));

        assertThat(toast).get(STRING).contains("joined").contains("Groceries");
        verify(gateway).sendMarkdown(eq(INVITER_CHAT_ID), contains("@bob accepted"), isNull());
    }

    @Test
    void decline_deletesMembership_notifiesInviter_andConfirmsToInvitee() {
        final UUID memberId = UUID.randomUUID();
        when(inviteService.respond(memberId, false))
                .thenReturn(new InviteResponseResult.Declined(member(), list("Groceries"), inviter()));

        final Optional<String> toast = handler.handle(callback("lm:decline:" + memberId, "bob"));

        assertThat(toast).get(STRING).contains("Declined");
        verify(gateway).sendMarkdown(eq(INVITER_CHAT_ID), contains("@bob declined"), isNull());
    }

    @Test
    void accept_withMissingInviter_skipsNotificationButStillConfirms() {
        final UUID memberId = UUID.randomUUID();
        when(inviteService.respond(memberId, true))
                .thenReturn(new InviteResponseResult.Accepted(member(), list("Groceries"), null));

        final Optional<String> toast = handler.handle(callback("lm:accept:" + memberId, "bob"));

        assertThat(toast).get(STRING).contains("joined");
        verify(gateway, never()).sendMarkdown(anyLong(), any(), any());
    }

    @Test
    void replayedAccept_onActiveMembership_doesNotNotifyInviterAgain() {
        final UUID memberId = UUID.randomUUID();
        when(inviteService.respond(memberId, true))
                .thenReturn(new InviteResponseResult.AlreadyActive(member()));

        final Optional<String> toast = handler.handle(callback("lm:accept:" + memberId, "bob"));

        assertThat(toast).get(STRING).contains("already a member");
        verify(gateway, never()).sendMarkdown(anyLong(), any(), any());
    }

    @Test
    void unknownMembership_isClaimedWithToast_butNotifiesNoOne() {
        final UUID memberId = UUID.randomUUID();
        when(inviteService.respond(memberId, false)).thenReturn(new InviteResponseResult.NotFound());

        final Optional<String> toast = handler.handle(callback("lm:decline:" + memberId, "bob"));

        assertThat(toast).get(STRING).contains("no longer valid");
        verify(gateway, never()).sendMarkdown(anyLong(), any(), any());
    }

    @Test
    void malformedMemberId_isClaimedWithToast_butTouchesNoService() {
        final Optional<String> toast = handler.handle(callback("lm:accept:not-a-uuid", "bob"));

        assertThat(toast).contains("Unknown invite");
        verifyNoInteractions(inviteService, gateway);
    }

    @Test
    void unrelatedCallbackData_isLeftUnclaimed() {
        final Optional<String> toast = handler.handle(callback("task:done:42", "bob"));

        assertThat(toast).isEmpty();
        verifyNoInteractions(inviteService, gateway);
    }

    @Test
    void nullCallbackData_isLeftUnclaimed() {
        final Optional<String> toast = handler.handle(callback(null, "bob"));

        assertThat(toast).isEmpty();
        verifyNoInteractions(inviteService, gateway);
    }

    private ListMember member() {
        final ListMember member = ListMember.of(UUID.randomUUID(), UUID.randomUUID(), ListMemberRole.MEMBER);
        member.setId(UUID.randomUUID());
        return member;
    }

    private TaskList list(final String name) {
        final TaskList list = TaskList.of(inviter(), name, ListType.CHECKLIST);
        list.setId(UUID.randomUUID());
        return list;
    }

    private User inviter() {
        final User inviter = User.create(INVITER_CHAT_ID, "alice", ZoneId.of("Europe/Kyiv"), "uk");
        inviter.setId(UUID.randomUUID());
        return inviter;
    }

    private CallbackQuery callback(final String data, final String inviteeUsername) {
        final org.telegram.telegrambots.meta.api.objects.User from =
                new org.telegram.telegrambots.meta.api.objects.User(100L, "Bob", false);
        from.setUserName(inviteeUsername);

        final CallbackQuery query = new CallbackQuery();
        query.setId("cb-id");
        query.setFrom(from);
        query.setData(data);
        return query;
    }
}
