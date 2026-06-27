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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class ListLifecycleServiceTest {

    @Mock
    private ListRepository listRepository;

    @Mock
    private ListPermissionService permissionService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private ListLifecycleService service;

    private final UUID actor = UUID.randomUUID();

    private TaskList listWithStatus(final ListStatus status) {
        final User owner = User.create(1L, "owner", ZoneId.of("Europe/Kyiv"), "uk");
        owner.setId(UUID.randomUUID());
        final TaskList list = TaskList.checklist(owner, "Groceries");
        list.setId(UUID.randomUUID());
        list.setStatus(status);
        return list;
    }

    @Test
    void markCompleted_transitionsActiveToCompleted_setsClosedAt_andPublishesEvent() {
        // Arrange
        final TaskList list = listWithStatus(ListStatus.ACTIVE);
        when(listRepository.findById(list.getId())).thenReturn(Optional.of(list));

        // Act
        final TaskList result = service.markCompleted(actor, list.getId());

        // Assert
        verify(permissionService).requireCanEditList(actor, list.getId());
        assertThat(result.getStatus()).isEqualTo(ListStatus.COMPLETED);
        assertThat(result.getClosedAt()).isNotNull();
        verify(eventPublisher).publishEvent(new ListCompletedEvent(list.getId(), actor));
    }

    @Test
    void markCompleted_throws_andDoesNotMutateOrPublish_whenNotActive() {
        // Arrange
        final TaskList list = listWithStatus(ListStatus.COMPLETED);
        when(listRepository.findById(list.getId())).thenReturn(Optional.of(list));

        // Act + Assert
        assertThatThrownBy(() -> service.markCompleted(actor, list.getId()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(list.getStatus()).isEqualTo(ListStatus.COMPLETED);
        assertThat(list.getClosedAt()).isNull();
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void markCompleted_throws_andSkipsList_whenPermissionDenied() {
        // Arrange
        final UUID listId = UUID.randomUUID();
        doThrow(new ListPermissionDeniedException(actor, listId, "edit list", ListMemberRole.MEMBER))
                .when(permissionService).requireCanEditList(actor, listId);

        // Act + Assert
        assertThatThrownBy(() -> service.markCompleted(actor, listId))
                .isInstanceOf(ListPermissionDeniedException.class);
        verify(listRepository, never()).findById(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void markCompleted_throws_whenListMissing() {
        final UUID listId = UUID.randomUUID();
        when(listRepository.findById(listId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.markCompleted(actor, listId))
                .isInstanceOf(IllegalArgumentException.class);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void markArchived_transitionsCompletedToArchived_setsArchivedAt_keepsClosedAt_andPublishesEvent() {
        // Arrange
        final TaskList list = listWithStatus(ListStatus.COMPLETED);
        final Instant closedAt = Instant.now().minusSeconds(3600);
        list.setClosedAt(closedAt);
        when(listRepository.findById(list.getId())).thenReturn(Optional.of(list));

        // Act
        final TaskList result = service.markArchived(actor, list.getId());

        // Assert
        verify(permissionService).requireCanEditList(actor, list.getId());
        assertThat(result.getStatus()).isEqualTo(ListStatus.ARCHIVED);
        assertThat(result.getArchivedAt()).isNotNull();
        assertThat(result.getClosedAt()).isEqualTo(closedAt);
        verify(eventPublisher).publishEvent(new ListArchivedEvent(list.getId(), actor));
    }

    @Test
    void markArchived_throws_whenActive_becauseListMustBeCompletedFirst() {
        // Arrange
        final TaskList list = listWithStatus(ListStatus.ACTIVE);
        when(listRepository.findById(list.getId())).thenReturn(Optional.of(list));

        // Act + Assert
        assertThatThrownBy(() -> service.markArchived(actor, list.getId()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(list.getStatus()).isEqualTo(ListStatus.ACTIVE);
        assertThat(list.getArchivedAt()).isNull();
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void markArchived_throws_whenAlreadyArchived() {
        // Arrange
        final TaskList list = listWithStatus(ListStatus.ARCHIVED);
        when(listRepository.findById(list.getId())).thenReturn(Optional.of(list));

        // Act + Assert
        assertThatThrownBy(() -> service.markArchived(actor, list.getId()))
                .isInstanceOf(IllegalStateException.class);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void reopen_transitionsCompletedToActive_clearsClosedAt_andPublishesEvent() {
        // Arrange
        final TaskList list = listWithStatus(ListStatus.COMPLETED);
        list.setClosedAt(Instant.now());
        when(listRepository.findById(list.getId())).thenReturn(Optional.of(list));

        // Act
        final TaskList result = service.reopen(actor, list.getId());

        // Assert
        verify(permissionService).requireCanEditList(actor, list.getId());
        assertThat(result.getStatus()).isEqualTo(ListStatus.ACTIVE);
        assertThat(result.getClosedAt()).isNull();
        verify(eventPublisher).publishEvent(new ListReopenedEvent(list.getId(), actor));
    }

    @Test
    void reopen_throws_whenActive() {
        final TaskList list = listWithStatus(ListStatus.ACTIVE);
        when(listRepository.findById(list.getId())).thenReturn(Optional.of(list));

        assertThatThrownBy(() -> service.reopen(actor, list.getId()))
                .isInstanceOf(IllegalStateException.class);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void reopen_throws_whenArchived_archivedListsNeedRestoreNotReopen() {
        final TaskList list = listWithStatus(ListStatus.ARCHIVED);
        when(listRepository.findById(list.getId())).thenReturn(Optional.of(list));

        assertThatThrownBy(() -> service.reopen(actor, list.getId()))
                .isInstanceOf(IllegalStateException.class);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void reopen_throws_andSkipsList_whenPermissionDenied() {
        final UUID listId = UUID.randomUUID();
        doThrow(new ListPermissionDeniedException(actor, listId, "edit list", ListMemberRole.MEMBER))
                .when(permissionService).requireCanEditList(actor, listId);

        assertThatThrownBy(() -> service.reopen(actor, listId))
                .isInstanceOf(ListPermissionDeniedException.class);
        verify(listRepository, never()).findById(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void findActiveByOwner_returnsActiveNonDeletedLists_fromRepository() {
        // Arrange
        final UUID ownerId = UUID.randomUUID();
        final TaskList active = listWithStatus(ListStatus.ACTIVE);
        when(listRepository.findByOwnerIdAndStatusAndArchivedAtIsNull(ownerId, ListStatus.ACTIVE))
                .thenReturn(List.of(active));

        // Act + Assert
        assertThat(service.findActiveByOwner(ownerId)).containsExactly(active);
    }

    @Test
    void findArchivedByOwner_rangesOnClosedAt_fromRepository() {
        // Arrange
        final UUID ownerId = UUID.randomUUID();
        final Instant from = Instant.now().minusSeconds(86_400);
        final Instant to = Instant.now();
        final TaskList archived = listWithStatus(ListStatus.ARCHIVED);
        when(listRepository.findByOwnerIdAndStatusAndClosedAtBetween(ownerId, ListStatus.ARCHIVED, from, to))
                .thenReturn(List.of(archived));

        // Act + Assert
        assertThat(service.findArchivedByOwner(ownerId, from, to)).containsExactly(archived);
    }
}
