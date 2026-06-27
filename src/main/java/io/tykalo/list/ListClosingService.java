package io.tykalo.list;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The carry-over logic behind closing a list (TK-254). Closing a list is {@link ListLifecycleService}'s
 * {@code markCompleted}; this service adds the three ways a user can deal with the list's still-unfinished
 * items first — save them for later, move them to another list, or drop them — then completes the list in
 * the same transaction so the carry-over and the close are atomic.
 *
 * <p>"Drop" needs nothing here: the unfinished items simply stay on the now-COMPLETED (read-only) list,
 * so the handler calls {@link ListLifecycleService#markCompleted} directly. Permission (OWNER/EDITOR to
 * edit the source list, MEMBER+ to add to a move target) is enforced at this boundary via
 * {@link ListPermissionService}; {@code markCompleted} re-checks it harmlessly.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ListClosingService {

    private final ListLifecycleService lifecycleService;
    private final ListPermissionService permissionService;
    private final ListRepository listRepository;
    private final TaskRepository taskRepository;
    private final PendingItemService pendingItemService;
    private final ApplicationEventPublisher eventPublisher;

    /** A list's still-unfinished items: live (non-archived) tasks that are not yet {@link TaskStatus#DONE}. */
    @Transactional(readOnly = true)
    public List<Task> unfinishedTasks(final UUID listId) {
        return taskRepository.findByListIdAndArchivedAtIsNullOrderByCreatedAtAsc(listId).stream()
                .filter(task -> task.getStatus() != TaskStatus.DONE)
                .toList();
    }

    /**
     * Closes the list, carrying its unfinished items into {@code actorId}'s pending bucket first: each is
     * deferred (snapshotting the source list's tags for TK-258), marked {@link TaskStatus#DEFERRED} and
     * stamped {@code archivedAt} so it leaves the list's active items — mirroring the per-task save in
     * {@link TaskService#saveForLater}. Then {@code markCompleted} flips the list to COMPLETED.
     */
    @Transactional
    public TaskList closeSavingForLater(final UUID actorId, final UUID listId) {
        permissionService.requireCanEditList(actorId, listId);
        final List<Task> unfinished = unfinishedTasks(listId);
        for (final Task task : unfinished) {
            pendingItemService.defer(actorId, task.getTitle(), listId, task.getId());
            task.setStatus(TaskStatus.DEFERRED);
            task.setArchivedAt(Instant.now());
        }
        log.info("Closing list {}: saved {} unfinished items for later (actor {})",
                listId, unfinished.size(), actorId);
        return lifecycleService.markCompleted(actorId, listId);
    }

    /**
     * Closes the list, moving its unfinished items into {@code targetListId} first: each unfinished task is
     * reassigned to the target (its {@code ownerId} is immutable and carries over). Then {@code markCompleted}
     * flips the source to COMPLETED. Throws if the target is the source itself or is not an active list;
     * {@link ListChangedEvent}s for both lists keep their live messages in sync.
     */
    @Transactional
    public TaskList closeMovingTo(final UUID actorId, final UUID listId, final UUID targetListId) {
        if (listId.equals(targetListId)) {
            throw new IllegalArgumentException("Cannot move items to the list being closed: " + listId);
        }
        permissionService.requireCanEditList(actorId, listId);
        permissionService.requireCanAddItems(actorId, targetListId);
        final TaskList target = listRepository.findById(targetListId)
                .orElseThrow(() -> new IllegalArgumentException("List not found: " + targetListId));
        if (target.getArchivedAt() != null || target.getStatus() != ListStatus.ACTIVE) {
            throw new IllegalStateException("Move target is not an active list: " + targetListId);
        }
        final List<Task> unfinished = unfinishedTasks(listId);
        unfinished.forEach(task -> task.setListId(targetListId));
        log.info("Closing list {}: moved {} unfinished items to list {} (actor {})",
                listId, unfinished.size(), targetListId, actorId);
        final TaskList completed = lifecycleService.markCompleted(actorId, listId);
        if (!unfinished.isEmpty()) {
            eventPublisher.publishEvent(new ListChangedEvent(listId));
            eventPublisher.publishEvent(new ListChangedEvent(targetListId));
        }
        return completed;
    }
}
