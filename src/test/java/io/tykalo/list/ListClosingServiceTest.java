package io.tykalo.list;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.tykalo.user.User;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class ListClosingServiceTest {

    @Mock
    private ListLifecycleService lifecycleService;

    @Mock
    private ListPermissionService permissionService;

    @Mock
    private ListRepository listRepository;

    @Mock
    private TaskRepository taskRepository;

    @Mock
    private PendingItemService pendingItemService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private ListClosingService service;

    private UUID actorId;
    private UUID listId;
    private TaskList list;

    @BeforeEach
    void setUp() {
        actorId = UUID.randomUUID();
        final User owner = User.create(100L, "owner", ZoneId.of("Europe/Kyiv"), "en");
        owner.setId(actorId);
        list = TaskList.of(owner, "Groceries", ListType.CHECKLIST);
        listId = UUID.randomUUID();
        list.setId(listId);
    }

    private Task task(final String title, final TaskStatus status) {
        final Task t = Task.create(list, title);
        t.setId(UUID.randomUUID());
        t.setStatus(status);
        return t;
    }

    @Test
    void unfinishedTasks_returnsLiveNonDoneTasksOnly() {
        final Task milk = task("Milk", TaskStatus.TODO);
        final Task eggs = task("Eggs", TaskStatus.DONE);
        final Task bread = task("Bread", TaskStatus.TODO);
        when(taskRepository.findByListIdAndArchivedAtIsNullOrderByCreatedAtAsc(listId))
                .thenReturn(List.of(milk, eggs, bread));

        assertThat(service.unfinishedTasks(listId)).containsExactly(milk, bread);
    }

    @Test
    void closeSavingForLater_defersAndArchivesUnfinished_thenCompletes() {
        final Task milk = task("Milk", TaskStatus.TODO);
        when(taskRepository.findByListIdAndArchivedAtIsNullOrderByCreatedAtAsc(listId)).thenReturn(List.of(milk));

        service.closeSavingForLater(actorId, listId);

        verify(permissionService).requireCanEditList(actorId, listId);
        verify(pendingItemService).defer(actorId, "Milk", listId, milk.getId());
        assertThat(milk.getStatus()).isEqualTo(TaskStatus.DEFERRED);
        assertThat(milk.getArchivedAt()).isNotNull();
        verify(lifecycleService).markCompleted(actorId, listId);
    }

    @Test
    void closeSavingForLater_completes_evenWithNoUnfinishedItems() {
        when(taskRepository.findByListIdAndArchivedAtIsNullOrderByCreatedAtAsc(listId)).thenReturn(List.of());

        service.closeSavingForLater(actorId, listId);

        verifyNoInteractions(pendingItemService);
        verify(lifecycleService).markCompleted(actorId, listId);
    }

    @Test
    void closeSavingForLater_propagatesPermissionDenial_andDoesNotComplete() {
        doThrow(new ListPermissionDeniedException(actorId, listId, "edit list", ListMemberRole.MEMBER))
                .when(permissionService).requireCanEditList(actorId, listId);

        assertThatThrownBy(() -> service.closeSavingForLater(actorId, listId))
                .isInstanceOf(ListPermissionDeniedException.class);

        verifyNoInteractions(pendingItemService);
        verify(lifecycleService, never()).markCompleted(any(), any());
    }

    @Test
    void closeMovingTo_reassignsUnfinishedToTarget_completes_andAnnouncesBothLists() {
        final UUID targetId = UUID.randomUUID();
        final TaskList target = TaskList.of(userOwner(), "Backlog", ListType.PROJECT);
        target.setId(targetId);
        final Task milk = task("Milk", TaskStatus.TODO);
        when(listRepository.findById(targetId)).thenReturn(java.util.Optional.of(target));
        when(taskRepository.findByListIdAndArchivedAtIsNullOrderByCreatedAtAsc(listId)).thenReturn(List.of(milk));

        service.closeMovingTo(actorId, listId, targetId);

        verify(permissionService).requireCanEditList(actorId, listId);
        verify(permissionService).requireCanAddItems(actorId, targetId);
        assertThat(milk.getListId()).isEqualTo(targetId);
        verify(lifecycleService).markCompleted(actorId, listId);
        verify(eventPublisher, times(2)).publishEvent(any(ListChangedEvent.class));
    }

    @Test
    void closeMovingTo_doesNotAnnounce_whenNoUnfinishedItems() {
        final UUID targetId = UUID.randomUUID();
        final TaskList target = TaskList.of(userOwner(), "Backlog", ListType.PROJECT);
        target.setId(targetId);
        when(listRepository.findById(targetId)).thenReturn(java.util.Optional.of(target));
        when(taskRepository.findByListIdAndArchivedAtIsNullOrderByCreatedAtAsc(listId)).thenReturn(List.of());

        service.closeMovingTo(actorId, listId, targetId);

        verify(lifecycleService).markCompleted(actorId, listId);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void closeMovingTo_rejectsMovingToSelf_beforeAnyPermissionCheck() {
        assertThatThrownBy(() -> service.closeMovingTo(actorId, listId, listId))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(permissionService, lifecycleService, taskRepository);
    }

    @Test
    void closeMovingTo_rejectsArchivedTarget() {
        final UUID targetId = UUID.randomUUID();
        final TaskList target = TaskList.of(userOwner(), "Backlog", ListType.PROJECT);
        target.setId(targetId);
        target.setArchivedAt(Instant.now());
        when(listRepository.findById(targetId)).thenReturn(java.util.Optional.of(target));

        assertThatThrownBy(() -> service.closeMovingTo(actorId, listId, targetId))
                .isInstanceOf(IllegalStateException.class);

        verify(lifecycleService, never()).markCompleted(eq(actorId), eq(listId));
    }

    @Test
    void closeMovingTo_rejectsCompletedTarget() {
        final UUID targetId = UUID.randomUUID();
        final TaskList target = TaskList.of(userOwner(), "Backlog", ListType.PROJECT);
        target.setId(targetId);
        target.setStatus(ListStatus.COMPLETED);
        when(listRepository.findById(targetId)).thenReturn(java.util.Optional.of(target));

        assertThatThrownBy(() -> service.closeMovingTo(actorId, listId, targetId))
                .isInstanceOf(IllegalStateException.class);

        verify(lifecycleService, never()).markCompleted(eq(actorId), eq(listId));
    }

    private User userOwner() {
        final User owner = User.create(100L, "owner", ZoneId.of("Europe/Kyiv"), "en");
        owner.setId(actorId);
        return owner;
    }
}
