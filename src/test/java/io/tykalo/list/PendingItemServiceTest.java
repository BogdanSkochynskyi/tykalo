package io.tykalo.list;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.tykalo.user.User;
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
class PendingItemServiceTest {

    @Mock
    private PendingItemRepository pendingItemRepository;

    @Mock
    private ListRepository listRepository;

    @Mock
    private TaskRepository taskRepository;

    @InjectMocks
    private PendingItemService service;

    private final UUID userId = UUID.randomUUID();

    private TaskList listWithTags(final List<String> tags) {
        final User owner = User.create(1L, "owner", ZoneId.of("Europe/Kyiv"), "uk");
        owner.setId(userId);
        final TaskList list = TaskList.checklist(owner, "Groceries");
        list.setId(UUID.randomUUID());
        list.setTags(tags);
        return list;
    }

    @Test
    void defer_copiesSourceListTags_andSaves() {
        // Arrange
        final TaskList source = listWithTags(List.of("shopping", "home"));
        final UUID sourceTaskId = UUID.randomUUID();
        when(listRepository.findById(source.getId())).thenReturn(Optional.of(source));
        when(pendingItemRepository.save(any(PendingItem.class))).thenAnswer(inv -> inv.getArgument(0));

        // Act
        final PendingItem result = service.defer(userId, "Buy milk", source.getId(), sourceTaskId);

        // Assert
        final ArgumentCaptor<PendingItem> captor = ArgumentCaptor.forClass(PendingItem.class);
        verify(pendingItemRepository).save(captor.capture());
        final PendingItem saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(userId);
        assertThat(saved.getTitle()).isEqualTo("Buy milk");
        assertThat(saved.getOriginalListId()).contains(source.getId());
        assertThat(saved.getOriginalListTags()).containsExactly("shopping", "home");
        assertThat(saved.getSourceTaskId()).contains(sourceTaskId);
        assertThat(result).isSameAs(saved);
    }

    @Test
    void defer_withNoSourceList_savesWithoutProvenanceOrTags() {
        // Arrange
        when(pendingItemRepository.save(any(PendingItem.class))).thenAnswer(inv -> inv.getArgument(0));

        // Act
        final PendingItem result = service.defer(userId, "Stray thought", null, null);

        // Assert
        assertThat(result.getOriginalListId()).isEmpty();
        assertThat(result.getOriginalListTags()).isEmpty();
        assertThat(result.getSourceTaskId()).isEmpty();
        verifyNoInteractions(listRepository);
    }

    @Test
    void findByTags_returnsEmpty_andSkipsRepository_whenNoTags() {
        // Act
        final List<PendingItem> result = service.findByTags(userId, List.of());

        // Assert
        assertThat(result).isEmpty();
        verifyNoInteractions(pendingItemRepository);
    }

    @Test
    void findByTags_delegatesToOverlapQuery_withTagsAsArray() {
        // Arrange
        final PendingItem match = PendingItem.defer(userId, "Buy milk", null, List.of("shopping"), null);
        when(pendingItemRepository.findByUserIdAndTagsOverlapping(userId, new String[] {"shopping", "home"}))
                .thenReturn(List.of(match));

        // Act
        final List<PendingItem> result = service.findByTags(userId, List.of("shopping", "home"));

        // Assert
        assertThat(result).containsExactly(match);
    }

    @Test
    void findAll_returnsNewestFirst_fromRepository() {
        // Arrange
        final PendingItem item = PendingItem.defer(userId, "Later", null, List.of(), null);
        when(pendingItemRepository.findByUserIdOrderByDeferredAtDesc(userId)).thenReturn(List.of(item));

        // Act + Assert
        assertThat(service.findAll(userId)).containsExactly(item);
    }

    @Test
    void restore_createsTaskInTargetList_andRemovesFromBucket() {
        // Arrange
        final UUID pendingId = UUID.randomUUID();
        final PendingItem item = PendingItem.defer(userId, "Buy milk", null, List.of(), null);
        final TaskList target = listWithTags(List.of());
        when(pendingItemRepository.findById(pendingId)).thenReturn(Optional.of(item));
        when(listRepository.findById(target.getId())).thenReturn(Optional.of(target));
        when(taskRepository.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));

        // Act
        final Task task = service.restore(pendingId, target.getId());

        // Assert
        assertThat(task.getTitle()).isEqualTo("Buy milk");
        assertThat(task.getListId()).isEqualTo(target.getId());
        verify(pendingItemRepository).delete(item);
    }

    @Test
    void restore_throws_andCreatesNoTask_whenPendingItemMissing() {
        // Arrange
        final UUID pendingId = UUID.randomUUID();
        when(pendingItemRepository.findById(pendingId)).thenReturn(Optional.empty());

        // Act + Assert
        assertThatThrownBy(() -> service.restore(pendingId, UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
        verify(taskRepository, never()).save(any());
        verify(pendingItemRepository, never()).delete(any());
    }

    @Test
    void restore_throws_andRemovesNothing_whenTargetListMissing() {
        // Arrange
        final UUID pendingId = UUID.randomUUID();
        final UUID targetListId = UUID.randomUUID();
        when(pendingItemRepository.findById(pendingId))
                .thenReturn(Optional.of(PendingItem.defer(userId, "Buy milk", null, List.of(), null)));
        when(listRepository.findById(targetListId)).thenReturn(Optional.empty());

        // Act + Assert
        assertThatThrownBy(() -> service.restore(pendingId, targetListId))
                .isInstanceOf(IllegalArgumentException.class);
        verify(taskRepository, never()).save(any());
        verify(pendingItemRepository, never()).delete(any());
    }

    @Test
    void delete_discardsThroughRepository() {
        // Arrange
        final UUID pendingId = UUID.randomUUID();

        // Act
        service.delete(pendingId);

        // Assert
        verify(pendingItemRepository).deleteById(pendingId);
    }
}
