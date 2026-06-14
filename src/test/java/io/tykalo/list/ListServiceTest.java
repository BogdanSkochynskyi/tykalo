package io.tykalo.list;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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

    @Test
    void rename_updatesName_andStrips() {
        final UUID id = UUID.randomUUID();
        final TaskList list = TaskList.checklist(owner(), "Old");
        when(listRepository.findById(id)).thenReturn(Optional.of(list));

        final TaskList result = listService.rename(id, "  New name  ");

        assertThat(result.getName()).isEqualTo("New name");
    }

    @Test
    void rename_rejectsBlankName() {
        assertThatThrownBy(() -> listService.rename(UUID.randomUUID(), "  "))
                .isInstanceOf(IllegalArgumentException.class);
        verify(listRepository, never()).findById(any());
    }

    @Test
    void changeType_updatesTypeAndDefaultNudgerPolicy() {
        final UUID id = UUID.randomUUID();
        final TaskList list = TaskList.checklist(owner(), "List");
        when(listRepository.findById(id)).thenReturn(Optional.of(list));

        final TaskList result = listService.changeType(id, ListType.PROJECT);

        assertThat(result.getType()).isEqualTo(ListType.PROJECT);
        assertThat(result.getNudgerDefaultPolicy()).isEqualTo(NudgerDefaultPolicy.ON_PER_TASK);
    }

    @Test
    void changeType_rejectsInbox() {
        assertThatThrownBy(() -> listService.changeType(UUID.randomUUID(), ListType.INBOX))
                .isInstanceOf(IllegalArgumentException.class);
        verify(listRepository, never()).findById(any());
    }

    @Test
    void changeType_throws_whenListMissing() {
        final UUID id = UUID.randomUUID();
        when(listRepository.findById(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> listService.changeType(id, ListType.ROUTINE))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
