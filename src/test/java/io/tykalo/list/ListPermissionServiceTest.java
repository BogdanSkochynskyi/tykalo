package io.tykalo.list;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import java.util.function.BiPredicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ListPermissionServiceTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID LIST = UUID.randomUUID();

    @Mock
    private ListMemberRepository listMemberRepository;

    @Mock
    private ListRepository listRepository;

    @org.mockito.InjectMocks
    private ListPermissionService service;

    private void roleIs(final ListMemberRole role) {
        final ListMember member = ListMember.of(LIST, USER, role);
        when(listMemberRepository.findByListIdAndUserId(LIST, USER)).thenReturn(Optional.of(member));
    }

    private void noMembership() {
        when(listMemberRepository.findByListIdAndUserId(LIST, USER)).thenReturn(Optional.empty());
        lenient().when(listRepository.findById(LIST)).thenReturn(Optional.empty());
    }

    @Test
    void owner_canDoEverything() {
        roleIs(ListMemberRole.OWNER);

        assertThat(service.canView(USER, LIST)).isTrue();
        assertThat(service.canAddItems(USER, LIST)).isTrue();
        assertThat(service.canToggleItems(USER, LIST)).isTrue();
        assertThat(service.canEditList(USER, LIST)).isTrue();
        assertThat(service.canManageMembers(USER, LIST)).isTrue();
        assertThat(service.canDeleteList(USER, LIST)).isTrue();
        assertThat(service.canTransferOwnership(USER, LIST)).isTrue();
    }

    @Test
    void editor_canDoEverythingExceptDeleteAndTransfer() {
        roleIs(ListMemberRole.EDITOR);

        assertThat(service.canView(USER, LIST)).isTrue();
        assertThat(service.canAddItems(USER, LIST)).isTrue();
        assertThat(service.canToggleItems(USER, LIST)).isTrue();
        assertThat(service.canEditList(USER, LIST)).isTrue();
        assertThat(service.canManageMembers(USER, LIST)).isTrue();
        assertThat(service.canDeleteList(USER, LIST)).isFalse();
        assertThat(service.canTransferOwnership(USER, LIST)).isFalse();
    }

    @Test
    void member_canOnlyViewAddAndToggle() {
        roleIs(ListMemberRole.MEMBER);

        assertThat(service.canView(USER, LIST)).isTrue();
        assertThat(service.canAddItems(USER, LIST)).isTrue();
        assertThat(service.canToggleItems(USER, LIST)).isTrue();
        assertThat(service.canEditList(USER, LIST)).isFalse();
        assertThat(service.canManageMembers(USER, LIST)).isFalse();
        assertThat(service.canDeleteList(USER, LIST)).isFalse();
        assertThat(service.canTransferOwnership(USER, LIST)).isFalse();
    }

    @Test
    void nonMember_canDoNothing() {
        noMembership();

        for (final BiPredicate<UUID, UUID> check : java.util.List.<BiPredicate<UUID, UUID>>of(
                service::canView, service::canAddItems, service::canToggleItems, service::canEditList,
                service::canManageMembers, service::canDeleteList, service::canTransferOwnership)) {
            assertThat(check.test(USER, LIST)).isFalse();
        }
    }

    @Test
    void ownerIdIsAuthority_whenNoMemberRows() {
        // Arrange: no membership row, but the user is the list's legacy owner_id (pre-TK-197 backfill)
        when(listMemberRepository.findByListIdAndUserId(LIST, USER)).thenReturn(Optional.empty());
        final TaskList list = new TaskList();
        list.setOwnerId(USER);
        when(listRepository.findById(LIST)).thenReturn(Optional.of(list));

        // Assert: resolves to OWNER
        assertThat(service.resolveRole(USER, LIST)).contains(ListMemberRole.OWNER);
        assertThat(service.canDeleteList(USER, LIST)).isTrue();
    }

    @Test
    void memberRowWins_overOwnerIdFallback() {
        // Arrange: explicit MEMBER row exists; owner_id fallback is never consulted
        roleIs(ListMemberRole.MEMBER);

        assertThat(service.resolveRole(USER, LIST)).contains(ListMemberRole.MEMBER);
        assertThat(service.canDeleteList(USER, LIST)).isFalse();
    }

    @Test
    void requireCanDeleteList_throwsForMember_withRoleInException() {
        roleIs(ListMemberRole.MEMBER);

        assertThatThrownBy(() -> service.requireCanDeleteList(USER, LIST))
                .isInstanceOfSatisfying(ListPermissionDeniedException.class, ex -> {
                    assertThat(ex.getRole()).isEqualTo(ListMemberRole.MEMBER);
                    assertThat(ex.getUserId()).isEqualTo(USER);
                    assertThat(ex.getListId()).isEqualTo(LIST);
                    assertThat(ex.getMessage()).contains("delete list").contains("MEMBER");
                });
    }

    @Test
    void requireCanDeleteList_throwsForNonMember_withNullRole() {
        noMembership();

        assertThatThrownBy(() -> service.requireCanDeleteList(USER, LIST))
                .isInstanceOfSatisfying(ListPermissionDeniedException.class, ex -> {
                    assertThat(ex.getRole()).isNull();
                    assertThat(ex.getMessage()).contains("none");
                });
    }

    @Test
    void requireCanAddItems_passesForMember() {
        roleIs(ListMemberRole.MEMBER);

        service.requireCanAddItems(USER, LIST);
    }

    @Test
    void requireCanEditList_throwsForMember() {
        roleIs(ListMemberRole.MEMBER);

        assertThatThrownBy(() -> service.requireCanEditList(USER, LIST))
                .isInstanceOf(ListPermissionDeniedException.class);
    }
}
