package io.tykalo.list;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.tykalo.user.User;
import io.tykalo.user.UserRepository;
import java.time.ZoneId;
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

@ExtendWith(MockitoExtension.class)
class ListMemberServiceTest {

    @Mock
    private ListMemberRepository listMemberRepository;
    @Mock
    private ListRepository listRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private ListPermissionService permissionService;

    @InjectMocks
    private ListMemberService service;

    private User owner;
    private User editor;
    private TaskList list;

    @BeforeEach
    void setUp() {
        owner = user("alice");
        editor = user("bob");
        list = TaskList.of(owner, "Groceries", ListType.CHECKLIST);
        list.setId(UUID.randomUUID());
    }

    @Test
    void activeMembers_filtersOutPendingInvites() {
        final ListMember active = member(editor, ListMemberRole.EDITOR, ListMemberStatus.ACTIVE);
        final ListMember pending = member(user("carol"), ListMemberRole.MEMBER, ListMemberStatus.PENDING);
        when(listMemberRepository.findByListId(list.getId())).thenReturn(List.of(active, pending));

        assertThat(service.activeMembers(list.getId())).containsExactly(active);
    }

    @Test
    void remove_deletesTheMember_andReturnsRemoved() {
        final ListMember target = member(editor, ListMemberRole.EDITOR, ListMemberStatus.ACTIVE);
        when(listMemberRepository.findById(target.getId())).thenReturn(Optional.of(target));
        when(permissionService.canManageMembers(owner.getId(), list.getId())).thenReturn(true);
        when(listRepository.findById(list.getId())).thenReturn(Optional.of(list));
        when(userRepository.findById(editor.getId())).thenReturn(Optional.of(editor));

        final RemoveMemberResult result = service.remove(owner.getId(), target.getId());

        assertThat(result).isInstanceOf(RemoveMemberResult.Removed.class);
        assertThat(((RemoveMemberResult.Removed) result).removedUser()).isEqualTo(editor);
        verify(listMemberRepository).delete(target);
    }

    @Test
    void remove_refusesToRemoveTheOwner() {
        final ListMember ownerRow = member(owner, ListMemberRole.OWNER, ListMemberStatus.ACTIVE);
        when(listMemberRepository.findById(ownerRow.getId())).thenReturn(Optional.of(ownerRow));
        when(permissionService.canManageMembers(editor.getId(), list.getId())).thenReturn(true);

        final RemoveMemberResult result = service.remove(editor.getId(), ownerRow.getId());

        assertThat(result).isInstanceOf(RemoveMemberResult.CannotRemoveOwner.class);
        verify(listMemberRepository, never()).delete(any());
    }

    @Test
    void remove_isDenied_whenActorCannotManageMembers() {
        final ListMember target = member(editor, ListMemberRole.MEMBER, ListMemberStatus.ACTIVE);
        when(listMemberRepository.findById(target.getId())).thenReturn(Optional.of(target));
        when(permissionService.canManageMembers(editor.getId(), list.getId())).thenReturn(false);

        final RemoveMemberResult result = service.remove(editor.getId(), target.getId());

        assertThat(result).isInstanceOf(RemoveMemberResult.Denied.class);
        verify(listMemberRepository, never()).delete(any());
    }

    @Test
    void remove_reportsNotFound_whenMembershipIsGone() {
        final UUID memberId = UUID.randomUUID();
        when(listMemberRepository.findById(memberId)).thenReturn(Optional.empty());

        assertThat(service.remove(owner.getId(), memberId)).isInstanceOf(RemoveMemberResult.NotFound.class);
    }

    @Test
    void transfer_demotesOwner_promotesTarget_andReassignsOwnerId() {
        final ListMember ownerRow = member(owner, ListMemberRole.OWNER, ListMemberStatus.ACTIVE);
        final ListMember target = member(editor, ListMemberRole.EDITOR, ListMemberStatus.ACTIVE);
        when(listMemberRepository.findById(target.getId())).thenReturn(Optional.of(target));
        when(permissionService.canTransferOwnership(owner.getId(), list.getId())).thenReturn(true);
        when(listRepository.findById(list.getId())).thenReturn(Optional.of(list));
        when(listMemberRepository.findByListIdAndUserId(list.getId(), owner.getId()))
                .thenReturn(Optional.of(ownerRow));
        when(userRepository.findById(editor.getId())).thenReturn(Optional.of(editor));
        when(userRepository.findById(owner.getId())).thenReturn(Optional.of(owner));

        final TransferOwnershipResult result = service.transferOwnership(owner.getId(), target.getId());

        assertThat(result).isInstanceOf(TransferOwnershipResult.Transferred.class);
        assertThat(target.getRole()).isEqualTo(ListMemberRole.OWNER);
        assertThat(ownerRow.getRole()).isEqualTo(ListMemberRole.EDITOR);
        assertThat(list.getOwnerId()).isEqualTo(editor.getId());
        verify(listRepository).save(list);
    }

    @Test
    void transfer_createsEditorRow_whenPreviousOwnerHadNoMembershipRow() {
        final ListMember target = member(editor, ListMemberRole.EDITOR, ListMemberStatus.ACTIVE);
        when(listMemberRepository.findById(target.getId())).thenReturn(Optional.of(target));
        when(permissionService.canTransferOwnership(owner.getId(), list.getId())).thenReturn(true);
        when(listRepository.findById(list.getId())).thenReturn(Optional.of(list));
        when(listMemberRepository.findByListIdAndUserId(list.getId(), owner.getId())).thenReturn(Optional.empty());
        when(userRepository.findById(editor.getId())).thenReturn(Optional.of(editor));
        when(userRepository.findById(owner.getId())).thenReturn(Optional.of(owner));

        service.transferOwnership(owner.getId(), target.getId());

        final ArgumentCaptor<ListMember> saved = ArgumentCaptor.forClass(ListMember.class);
        verify(listMemberRepository, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        assertThat(saved.getAllValues()).anySatisfy(member -> {
            assertThat(member.getUserId()).isEqualTo(owner.getId());
            assertThat(member.getRole()).isEqualTo(ListMemberRole.EDITOR);
        });
    }

    @Test
    void transfer_isDenied_whenActorIsNotOwner() {
        final ListMember target = member(editor, ListMemberRole.EDITOR, ListMemberStatus.ACTIVE);
        when(listMemberRepository.findById(target.getId())).thenReturn(Optional.of(target));
        when(permissionService.canTransferOwnership(editor.getId(), list.getId())).thenReturn(false);

        assertThat(service.transferOwnership(editor.getId(), target.getId()))
                .isInstanceOf(TransferOwnershipResult.Denied.class);
        verify(listRepository, never()).save(any());
    }

    @Test
    void transfer_reportsInvalidTarget_whenTargetIsAlreadyOwner() {
        final ListMember target = member(editor, ListMemberRole.OWNER, ListMemberStatus.ACTIVE);
        when(listMemberRepository.findById(target.getId())).thenReturn(Optional.of(target));
        when(permissionService.canTransferOwnership(owner.getId(), list.getId())).thenReturn(true);

        assertThat(service.transferOwnership(owner.getId(), target.getId()))
                .isInstanceOf(TransferOwnershipResult.InvalidTarget.class);
    }

    @Test
    void transfer_reportsInvalidTarget_whenTargetMembershipIsGone() {
        final UUID memberId = UUID.randomUUID();
        when(listMemberRepository.findById(memberId)).thenReturn(Optional.empty());

        assertThat(service.transferOwnership(owner.getId(), memberId))
                .isInstanceOf(TransferOwnershipResult.InvalidTarget.class);
    }

    private ListMember member(final User user, final ListMemberRole role, final ListMemberStatus status) {
        final ListMember member = ListMember.of(list.getId(), user.getId(), role);
        member.setStatus(status);
        member.setId(UUID.randomUUID());
        return member;
    }

    private User user(final String username) {
        final User user = User.create((long) username.hashCode(), username, ZoneId.of("Europe/Kyiv"), "uk");
        user.setId(UUID.randomUUID());
        return user;
    }
}
