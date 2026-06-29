package io.tykalo.list;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.tykalo.user.User;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ListServiceTest {

    @Mock
    private ListRepository listRepository;

    @Mock
    private ListMemberRepository listMemberRepository;

    @Mock
    private ListPermissionService permissionService;

    @InjectMocks
    private ListService listService;

    private User owner() {
        final User user = User.create(1L, "owner", ZoneId.of("Europe/Kyiv"), "uk");
        user.setId(UUID.randomUUID());
        return user;
    }

    @Test
    void createList_savesListOfGivenType_andStripsName() {
        // Arrange
        final User owner = owner();
        when(listRepository.save(any(TaskList.class))).thenAnswer(inv -> inv.getArgument(0));

        // Act
        final TaskList result = listService.createList(owner, "  Groceries  ", ListType.PROJECT);

        // Assert
        final ArgumentCaptor<TaskList> saved = ArgumentCaptor.forClass(TaskList.class);
        verify(listRepository).save(saved.capture());
        assertThat(saved.getValue().getName()).isEqualTo("Groceries");
        assertThat(saved.getValue().getType()).isEqualTo(ListType.PROJECT);
        assertThat(saved.getValue().getOwnerId()).isEqualTo(owner.getId());
        assertThat(saved.getValue().getNudgerDefaultPolicy()).isEqualTo(NudgerDefaultPolicy.ON_PER_TASK);
        assertThat(result).isSameAs(saved.getValue());
    }

    @Test
    void createInbox_createsInboxList_namedInbox_withNudgersOff() {
        // Arrange
        final User owner = owner();
        when(listRepository.save(any(TaskList.class))).thenAnswer(inv -> inv.getArgument(0));

        // Act
        final TaskList result = listService.createInbox(owner);

        // Assert
        final ArgumentCaptor<TaskList> saved = ArgumentCaptor.forClass(TaskList.class);
        verify(listRepository).save(saved.capture());
        assertThat(saved.getValue().getName()).isEqualTo("Inbox");
        assertThat(saved.getValue().getType()).isEqualTo(ListType.INBOX);
        assertThat(saved.getValue().getNudgerDefaultPolicy()).isEqualTo(NudgerDefaultPolicy.OFF);
        assertThat(saved.getValue().getOwnerId()).isEqualTo(owner.getId());
        assertThat(result).isSameAs(saved.getValue());
    }

    @Test
    void createList_rejectsBlankName() {
        final User owner = owner();
        assertThatThrownBy(() -> listService.createList(owner, "   ", ListType.CHECKLIST))
                .isInstanceOf(IllegalArgumentException.class);
        verify(listRepository, never()).save(any());
    }

    @Test
    void findAllByOwner_returnsOnlyActiveLists() {
        // Arrange
        final UUID ownerId = UUID.randomUUID();
        final TaskList active = TaskList.checklist(owner(), "Active");
        when(listRepository.findByOwnerIdAndArchivedAtIsNull(ownerId)).thenReturn(List.of(active));

        // Act
        final List<TaskList> result = listService.findAllByOwner(ownerId);

        // Assert
        assertThat(result).containsExactly(active);
    }

    @Test
    void findActiveByName_matchesCaseInsensitively_amongActiveLists() {
        // Arrange
        final UUID ownerId = UUID.randomUUID();
        final TaskList groceries = TaskList.checklist(owner(), "Groceries");
        when(listRepository.findByOwnerIdAndArchivedAtIsNull(ownerId))
                .thenReturn(List.of(groceries));

        // Act
        final Optional<TaskList> result = listService.findActiveByName(ownerId, "  groceries ");

        // Assert
        assertThat(result).containsSame(groceries);
    }

    @Test
    void findActiveByName_returnsEmpty_whenNoActiveMatch() {
        final UUID ownerId = UUID.randomUUID();
        when(listRepository.findByOwnerIdAndArchivedAtIsNull(ownerId))
                .thenReturn(List.of(TaskList.checklist(owner(), "Groceries")));

        assertThat(listService.findActiveByName(ownerId, "Chores")).isEmpty();
    }

    @Test
    void findActiveByName_returnsEmpty_forBlankName() {
        assertThat(listService.findActiveByName(UUID.randomUUID(), "  ")).isEmpty();
        verify(listRepository, never()).findByOwnerIdAndArchivedAtIsNull(any());
    }

    @Test
    void deleteList_softDeletes_bySettingArchivedAt() {
        // Arrange
        final UUID id = UUID.randomUUID();
        final TaskList list = TaskList.checklist(owner(), "Doomed");
        when(listRepository.findById(id)).thenReturn(Optional.of(list));

        // Act
        listService.deleteList(id);

        // Assert
        assertThat(list.getArchivedAt()).isNotNull();
    }

    @Test
    void deleteList_isNoOp_whenAlreadyArchived() {
        // Arrange
        final UUID id = UUID.randomUUID();
        final TaskList list = TaskList.checklist(owner(), "Doomed");
        final Instant firstArchive = Instant.now().minusSeconds(60);
        list.setArchivedAt(firstArchive);
        when(listRepository.findById(id)).thenReturn(Optional.of(list));

        // Act
        listService.deleteList(id);

        // Assert
        assertThat(list.getArchivedAt()).isEqualTo(firstArchive);
    }

    @Test
    void deleteList_throws_whenListMissing() {
        final UUID id = UUID.randomUUID();
        when(listRepository.findById(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> listService.deleteList(id))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private TaskList listWithId(final String name) {
        final TaskList list = TaskList.checklist(owner(), name);
        list.setId(UUID.randomUUID());
        return list;
    }

    @Test
    void deleteList_actorAware_checksPermissionThenSoftDeletes() {
        // Arrange
        final UUID actor = UUID.randomUUID();
        final TaskList list = listWithId("Shared");
        when(listRepository.findById(list.getId())).thenReturn(Optional.of(list));

        // Act
        listService.deleteList(actor, list.getId());

        // Assert
        verify(permissionService).requireCanDeleteList(actor, list.getId());
        assertThat(list.getArchivedAt()).isNotNull();
    }

    @Test
    void deleteList_actorAware_throws_andDoesNotTouchList_whenPermissionDenied() {
        // Arrange
        final UUID actor = UUID.randomUUID();
        final UUID listId = UUID.randomUUID();
        doThrow(new ListPermissionDeniedException(actor, listId, "delete list", ListMemberRole.MEMBER))
                .when(permissionService).requireCanDeleteList(actor, listId);

        // Act + Assert
        assertThatThrownBy(() -> listService.deleteList(actor, listId))
                .isInstanceOf(ListPermissionDeniedException.class);
        verify(listRepository, never()).findById(any());
    }

    @Test
    void setAutoClose_checksEditPermission_andUpdatesTheFlag() {
        // Arrange
        final UUID actor = UUID.randomUUID();
        final TaskList list = listWithId("Groceries");
        when(listRepository.findById(list.getId())).thenReturn(Optional.of(list));

        // Act
        final TaskList result = listService.setAutoClose(actor, list.getId(), true);

        // Assert
        verify(permissionService).requireCanEditList(actor, list.getId());
        assertThat(result.isAutoClose()).isTrue();
    }

    @Test
    void setAutoClose_throws_andDoesNotTouchList_whenPermissionDenied() {
        // Arrange
        final UUID actor = UUID.randomUUID();
        final UUID listId = UUID.randomUUID();
        doThrow(new ListPermissionDeniedException(actor, listId, "edit list", ListMemberRole.MEMBER))
                .when(permissionService).requireCanEditList(actor, listId);

        // Act + Assert
        assertThatThrownBy(() -> listService.setAutoClose(actor, listId, true))
                .isInstanceOf(ListPermissionDeniedException.class);
        verify(listRepository, never()).findById(any());
    }

    @Test
    void findAllAccessibleLists_unionsOwnedAndMemberLists_excludingArchivedMemberLists() {
        // Arrange
        final UUID userId = UUID.randomUUID();
        final TaskList owned = listWithId("Owned");
        final TaskList sharedActive = listWithId("Shared");
        final TaskList sharedArchived = listWithId("OldShared");
        sharedArchived.setArchivedAt(Instant.now());
        when(listRepository.findByOwnerIdAndArchivedAtIsNull(userId)).thenReturn(List.of(owned));
        when(listMemberRepository.findByUserIdAndStatus(userId, ListMemberStatus.ACTIVE)).thenReturn(List.of(
                ListMember.of(sharedActive.getId(), userId, ListMemberRole.MEMBER),
                ListMember.of(sharedArchived.getId(), userId, ListMemberRole.EDITOR)));
        when(listRepository.findAllById(List.of(sharedActive.getId(), sharedArchived.getId())))
                .thenReturn(List.of(sharedActive, sharedArchived));

        // Act
        final List<TaskList> result = listService.findAllAccessibleLists(userId);

        // Assert
        assertThat(result).containsExactly(owned, sharedActive);
    }

    @Test
    void findAllAccessibleLists_dedupesListWhereUserIsBothOwnerAndMember() {
        // Arrange
        final UUID userId = UUID.randomUUID();
        final TaskList owned = listWithId("Owned");
        when(listRepository.findByOwnerIdAndArchivedAtIsNull(userId)).thenReturn(List.of(owned));
        when(listMemberRepository.findByUserIdAndStatus(userId, ListMemberStatus.ACTIVE))
                .thenReturn(List.of(ListMember.owner(owned.getId(), userId)));
        when(listRepository.findAllById(List.of(owned.getId()))).thenReturn(List.of(owned));

        // Act
        final List<TaskList> result = listService.findAllAccessibleLists(userId);

        // Assert
        assertThat(result).containsExactly(owned);
    }
}
