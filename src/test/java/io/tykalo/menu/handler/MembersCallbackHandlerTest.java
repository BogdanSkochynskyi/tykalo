package io.tykalo.menu.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.tykalo.list.ListMemberService;
import io.tykalo.list.ListType;
import io.tykalo.list.RemoveMemberResult;
import io.tykalo.list.TaskList;
import io.tykalo.list.TransferOwnershipResult;
import io.tykalo.menu.InviteMemberService;
import io.tykalo.menu.ListViewService;
import io.tykalo.menu.MembersService;
import io.tykalo.user.User;
import io.tykalo.user.UserRepository;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;

@ExtendWith(MockitoExtension.class)
class MembersCallbackHandlerTest {

    private static final long CHAT_ID = 100L;
    private static final long REMOVED_CHAT_ID = 200L;
    private static final int MESSAGE_ID = 7;

    @Mock
    private UserRepository userRepository;
    @Mock
    private MembersService membersService;
    @Mock
    private ListMemberService listMemberService;
    @Mock
    private InviteMemberService inviteMemberService;
    @Mock
    private ListViewService listViewService;
    @Mock
    private io.tykalo.telegram.TelegramMessageGateway gateway;

    @InjectMocks
    private MembersCallbackHandler handler;

    private User owner;
    private TaskList list;

    @BeforeEach
    void setUp() {
        owner = User.create(CHAT_ID, "alice", ZoneId.of("Europe/Kyiv"), "en");
        owner.setId(UUID.randomUUID());
        list = TaskList.of(owner, "Groceries", ListType.CHECKLIST);
        list.setId(UUID.randomUUID());
    }

    private void stubUser() {
        when(userRepository.findByTgChatId(CHAT_ID)).thenReturn(Optional.of(owner));
    }

    @Test
    void nonMembersCallback_isLeftUnclaimed() {
        assertThat(handler.handle(callback("lv:more:" + list.getId()))).isEmpty();
    }

    @Test
    void reportsExpired_whenMessageIsGone() {
        assertThat(handler.handle(callback(MembersService.OPEN_PREFIX + list.getId())))
                .get().asString().contains("expired");
    }

    @Test
    void open_rendersTheMembersScreen() {
        stubUser();
        when(membersService.open(owner, MESSAGE_ID, list.getId())).thenReturn(Optional.of("Groceries"));

        assertThat(handler.handle(onMessage(MembersService.OPEN_PREFIX + list.getId())))
                .get().asString().contains("Members");
        verify(membersService).open(owner, MESSAGE_ID, list.getId());
    }

    @Test
    void cancelInvite_returnsToMembersScreen() {
        stubUser();
        when(membersService.open(owner, MESSAGE_ID, list.getId())).thenReturn(Optional.of("Groceries"));

        assertThat(handler.handle(onMessage(InviteMemberService.CANCEL_PREFIX + list.getId())))
                .get().asString().contains("Members");
        verify(membersService).open(owner, MESSAGE_ID, list.getId());
    }

    @Test
    void back_returnsToTheListView() {
        stubUser();
        when(listViewService.show(owner, MESSAGE_ID, list.getId(), 0)).thenReturn(Optional.of("Groceries"));

        assertThat(handler.handle(onMessage(MembersService.BACK_PREFIX + list.getId())))
                .get().asString().contains("List");
        verify(listViewService).show(owner, MESSAGE_ID, list.getId(), 0);
    }

    @Test
    void invite_startsTheInviteFlow() {
        stubUser();
        when(inviteMemberService.start(owner, MESSAGE_ID, list.getId())).thenReturn(Optional.of("Groceries"));

        assertThat(handler.handle(onMessage(MembersService.INVITE_PREFIX + list.getId())))
                .get().asString().contains("Invite");
    }

    @Test
    void invite_isRefused_whenUserCannotManage() {
        stubUser();
        when(inviteMemberService.start(owner, MESSAGE_ID, list.getId())).thenReturn(Optional.empty());

        assertThat(handler.handle(onMessage(MembersService.INVITE_PREFIX + list.getId())))
                .get().asString().contains("Only owners and editors");
    }

    @Test
    void shareLink_rendersTheLinkView() {
        stubUser();
        when(membersService.showShareLink(owner, MESSAGE_ID, list.getId())).thenReturn(Optional.of("Groceries"));

        assertThat(handler.handle(onMessage(MembersService.LINK_PREFIX + list.getId())))
                .get().asString().contains("Share link");
    }

    @Test
    void transfer_opensThePicker() {
        stubUser();
        when(membersService.showTransferPicker(owner, MESSAGE_ID, list.getId()))
                .thenReturn(Optional.of("Groceries"));

        assertThat(handler.handle(onMessage(MembersService.TRANSFER_PREFIX + list.getId())))
                .get().asString().contains("Transfer");
    }

    @Test
    void removePrompt_showsConfirmation() {
        stubUser();
        final UUID memberId = UUID.randomUUID();
        when(membersService.showRemoveConfirm(owner, MESSAGE_ID, memberId)).thenReturn(Optional.of("Groceries"));

        assertThat(handler.handle(onMessage(MembersService.REMOVE_PREFIX + memberId)))
                .get().asString().contains("Confirm");
    }

    @Test
    void removeConfirm_removesMember_notifiesThem_andReRendersMembers() {
        stubUser();
        final UUID memberId = UUID.randomUUID();
        final User removed = User.create(REMOVED_CHAT_ID, "bob", ZoneId.of("Europe/Kyiv"), "en");
        removed.setId(UUID.randomUUID());
        when(listMemberService.remove(owner.getId(), memberId))
                .thenReturn(new RemoveMemberResult.Removed(list, removed));

        assertThat(handler.handle(onMessage(MembersService.REMOVE_CONFIRM_PREFIX + memberId)))
                .get().asString().contains("removed");
        verify(gateway).sendMarkdown(eq(REMOVED_CHAT_ID), any(), eq(null));
        verify(membersService).open(owner, MESSAGE_ID, list.getId());
    }

    @Test
    void removeConfirm_reportsOwnerCannotBeRemoved() {
        stubUser();
        final UUID memberId = UUID.randomUUID();
        when(listMemberService.remove(owner.getId(), memberId))
                .thenReturn(new RemoveMemberResult.CannotRemoveOwner());

        assertThat(handler.handle(onMessage(MembersService.REMOVE_CONFIRM_PREFIX + memberId)))
                .get().asString().contains("transfer ownership first");
        verify(membersService, never()).open(any(), org.mockito.ArgumentMatchers.anyInt(), any());
    }

    @Test
    void transferConfirm_transfersOwnership_notifiesNewOwner_andReRenders() {
        stubUser();
        final UUID memberId = UUID.randomUUID();
        final User newOwner = User.create(REMOVED_CHAT_ID, "bob", ZoneId.of("Europe/Kyiv"), "en");
        newOwner.setId(UUID.randomUUID());
        when(listMemberService.transferOwnership(owner.getId(), memberId))
                .thenReturn(new TransferOwnershipResult.Transferred(list, owner, newOwner));

        assertThat(handler.handle(onMessage(MembersService.TRANSFER_CONFIRM_PREFIX + memberId)))
                .get().asString().contains("transferred");
        verify(gateway).sendMarkdown(eq(REMOVED_CHAT_ID), any(), eq(null));
        verify(membersService).open(owner, MESSAGE_ID, list.getId());
    }

    private CallbackQuery callback(final String data) {
        final CallbackQuery query = new CallbackQuery();
        query.setId("cb-id");
        query.setData(data);
        return query;
    }

    private CallbackQuery onMessage(final String data) {
        final Message message = new Message();
        message.setMessageId(MESSAGE_ID);
        message.setChat(new Chat(CHAT_ID, "private"));
        final CallbackQuery query = callback(data);
        query.setMessage(message);
        return query;
    }
}
