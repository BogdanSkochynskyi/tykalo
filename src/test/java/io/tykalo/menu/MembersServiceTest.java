package io.tykalo.menu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.tykalo.list.ListInviteService;
import io.tykalo.list.ListMember;
import io.tykalo.list.ListMemberRepository;
import io.tykalo.list.ListMemberRole;
import io.tykalo.list.ListMemberService;
import io.tykalo.list.ListPermissionService;
import io.tykalo.list.ListService;
import io.tykalo.list.ListType;
import io.tykalo.list.TaskList;
import io.tykalo.telegram.TelegramMessageGateway;
import io.tykalo.telegram.conversation.ConversationState;
import io.tykalo.telegram.conversation.ConversationStateService;
import io.tykalo.user.User;
import io.tykalo.user.UserRepository;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

@ExtendWith(MockitoExtension.class)
class MembersServiceTest {

    private static final long CHAT_ID = 100L;
    private static final int MESSAGE_ID = 7;

    @Mock
    private ListService listService;
    @Mock
    private ListMemberService listMemberService;
    @Mock
    private ListMemberRepository listMemberRepository;
    @Mock
    private ListPermissionService permissionService;
    @Mock
    private ListInviteService inviteService;
    @Mock
    private UserRepository userRepository;
    @Mock
    private ConversationStateService conversationState;
    @Mock
    private TelegramMessageGateway gateway;

    @InjectMocks
    private MembersService service;

    private User owner;
    private User editor;
    private User member;
    private TaskList list;
    private ListMember editorRow;
    private ListMember memberRow;

    @BeforeEach
    void setUp() {
        owner = user("alice", CHAT_ID);
        editor = user("bob", 200L);
        member = user("carol", 300L);
        list = TaskList.of(owner, "Groceries", ListType.CHECKLIST);
        list.setId(UUID.randomUUID());
        editorRow = row(editor, ListMemberRole.EDITOR);
        memberRow = row(member, ListMemberRole.MEMBER);
    }

    @Test
    void open_owner_seesEveryMember_removeButtons_andTransfer() {
        stubMembers(owner, true, true);

        final Optional<String> result = service.open(owner, MESSAGE_ID, list.getId());

        assertThat(result).contains("Groceries");
        verify(conversationState).setState(owner.getId(), new ConversationState.MembersScreen(list.getId()));
        assertThat(capturedText())
                .contains("👑 @alice — Owner")    // synthesized from owner_id (no OWNER row)
                .contains("✏️ @bob — Editor")
                .contains("👤 @carol — Member");
        final List<String> data = callbackData();
        assertThat(data).contains(
                MembersService.REMOVE_PREFIX + editorRow.getId(),
                MembersService.REMOVE_PREFIX + memberRow.getId(),
                MembersService.INVITE_PREFIX + list.getId(),
                MembersService.LINK_PREFIX + list.getId(),
                MembersService.TRANSFER_PREFIX + list.getId(),
                MembersService.BACK_PREFIX + list.getId());
    }

    @Test
    void open_editor_seesRemoveAndInvite_butNoTransfer() {
        stubMembers(editor, true, false);

        service.open(editor, MESSAGE_ID, list.getId());

        final List<String> data = callbackData();
        assertThat(data).contains(
                MembersService.REMOVE_PREFIX + editorRow.getId(),
                MembersService.INVITE_PREFIX + list.getId(),
                MembersService.LINK_PREFIX + list.getId(),
                MembersService.BACK_PREFIX + list.getId());
        assertThat(data).noneMatch(d -> d.startsWith(MembersService.TRANSFER_PREFIX));
    }

    @Test
    void open_member_seesReadOnlyList_withOnlyBack() {
        stubMembers(member, false, false);

        service.open(member, MESSAGE_ID, list.getId());

        assertThat(capturedText()).contains("only access to this list");
        final List<String> data = callbackData();
        assertThat(data).containsExactly(MembersService.BACK_PREFIX + list.getId());
    }

    @Test
    void open_returnsEmpty_whenUserHasNoAccess() {
        when(listService.getActiveById(list.getId())).thenReturn(Optional.of(list));
        when(permissionService.resolveRole(member.getId(), list.getId())).thenReturn(Optional.empty());

        assertThat(service.open(member, MESSAGE_ID, list.getId())).isEmpty();
        verify(gateway, never()).editMarkdown(anyLong(), anyInt(), anyString(), any());
    }

    @Test
    void showRemoveConfirm_rendersConfirmation_withYesAndCancel() {
        when(listMemberRepository.findById(editorRow.getId())).thenReturn(Optional.of(editorRow));
        when(permissionService.canManageMembers(owner.getId(), list.getId())).thenReturn(true);
        when(listService.getActiveById(list.getId())).thenReturn(Optional.of(list));
        when(userRepository.findById(editor.getId())).thenReturn(Optional.of(editor));

        final Optional<String> result = service.showRemoveConfirm(owner, MESSAGE_ID, editorRow.getId());

        assertThat(result).contains("Groceries");
        assertThat(capturedText()).contains("Remove @bob");
        assertThat(callbackData()).containsExactly(
                MembersService.REMOVE_CONFIRM_PREFIX + editorRow.getId(),
                MembersService.OPEN_PREFIX + list.getId());
    }

    @Test
    void showRemoveConfirm_refusesToConfirmRemovingTheOwner() {
        final ListMember ownerRow = row(owner, ListMemberRole.OWNER);
        when(listMemberRepository.findById(ownerRow.getId())).thenReturn(Optional.of(ownerRow));

        assertThat(service.showRemoveConfirm(editor, MESSAGE_ID, ownerRow.getId())).isEmpty();
        verify(gateway, never()).editMarkdown(anyLong(), anyInt(), anyString(), any());
    }

    @Test
    void showTransferPicker_listsNonOwnerMembers() {
        when(permissionService.canTransferOwnership(owner.getId(), list.getId())).thenReturn(true);
        when(listService.getActiveById(list.getId())).thenReturn(Optional.of(list));
        when(listMemberService.activeMembers(list.getId())).thenReturn(List.of(editorRow, memberRow));
        when(userRepository.findAllById(any())).thenReturn(List.of(editor, member));

        final Optional<String> result = service.showTransferPicker(owner, MESSAGE_ID, list.getId());

        assertThat(result).contains("Groceries");
        assertThat(callbackData()).containsExactly(
                MembersService.TRANSFER_TO_PREFIX + editorRow.getId(),
                MembersService.TRANSFER_TO_PREFIX + memberRow.getId(),
                MembersService.OPEN_PREFIX + list.getId());
    }

    @Test
    void showShareLink_rendersTheLink() {
        when(permissionService.canManageMembers(owner.getId(), list.getId())).thenReturn(true);
        when(listService.getActiveById(list.getId())).thenReturn(Optional.of(list));
        when(inviteService.createShareLink(owner, list.getId(), ListMemberRole.MEMBER))
                .thenReturn("https://t.me/TykaloBot?start=list_invite_x");

        final Optional<String> result = service.showShareLink(owner, MESSAGE_ID, list.getId());

        assertThat(result).contains("Groceries");
        assertThat(capturedText()).contains("Share link").contains("list");
        assertThat(callbackData()).containsExactly(MembersService.OPEN_PREFIX + list.getId());
    }

    private void stubMembers(final User viewer, final boolean canManage, final boolean canTransfer) {
        when(listService.getActiveById(list.getId())).thenReturn(Optional.of(list));
        when(permissionService.resolveRole(viewer.getId(), list.getId()))
                .thenReturn(Optional.of(ListMemberRole.OWNER));
        when(permissionService.canManageMembers(viewer.getId(), list.getId())).thenReturn(canManage);
        when(permissionService.canTransferOwnership(viewer.getId(), list.getId())).thenReturn(canTransfer);
        when(listMemberService.activeMembers(list.getId())).thenReturn(List.of(editorRow, memberRow));
        when(userRepository.findAllById(any())).thenReturn(List.of(owner, editor, member));
    }

    private String capturedText() {
        final ArgumentCaptor<String> text = ArgumentCaptor.captor();
        verify(gateway).editMarkdown(anyLong(), eq(MESSAGE_ID), text.capture(), any());
        return text.getValue();
    }

    private List<String> callbackData() {
        final ArgumentCaptor<InlineKeyboardMarkup> keyboard = ArgumentCaptor.captor();
        verify(gateway).editMarkdown(anyLong(), eq(MESSAGE_ID), anyString(), keyboard.capture());
        return keyboard.getValue().getKeyboard().stream()
                .flatMap(Collection::stream)
                .map(InlineKeyboardButton::getCallbackData)
                .toList();
    }

    private ListMember row(final User user, final ListMemberRole role) {
        final ListMember row = ListMember.of(list.getId(), user.getId(), role);
        row.setId(UUID.randomUUID());
        return row;
    }

    private User user(final String username, final long chatId) {
        final User user = User.create(chatId, username, ZoneId.of("Europe/Kyiv"), "uk");
        user.setId(UUID.randomUUID());
        return user;
    }
}
