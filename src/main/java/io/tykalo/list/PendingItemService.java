package io.tykalo.list;

import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The "saved for later" bucket (TK-255): foundation for the close-list carry-over (TK-254), "save for
 * later" from a task (TK-256), the pending screen (TK-257) and tag-matched suggestions on new lists
 * (TK-258). Pending items live independently of the list lifecycle — deferring removes nothing from a
 * list, and restoring re-materialises the item as a {@link Task} in a target list.
 *
 * <p>Permission is intentionally not enforced here: these methods carry no actor and run within a flow
 * that has already authorised the user at the handler boundary (e.g. MEMBER+ to add items in TK-256).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PendingItemService {

    private final PendingItemRepository pendingItemRepository;
    private final ListRepository listRepository;
    private final TaskRepository taskRepository;

    /**
     * Defers {@code title} for {@code userId}, capturing the source list ({@code sourceListId}) and a
     * snapshot of its tags (for tag matching in TK-258) plus the optional {@code sourceTaskId}. A
     * {@code null} {@code sourceListId} defers with no provenance and no tags.
     */
    @Transactional
    public PendingItem defer(final UUID userId, final String title, final @Nullable UUID sourceListId,
            final @Nullable UUID sourceTaskId) {
        final List<String> tags = sourceListId == null ? List.of()
                : listRepository.findById(sourceListId).map(TaskList::getTags).orElse(List.of());
        final PendingItem saved =
                pendingItemRepository.save(PendingItem.defer(userId, title, sourceListId, tags, sourceTaskId));
        log.info("Deferred '{}' for user {} (pending {})", title, userId, saved.getId());
        return saved;
    }

    /**
     * A user's pending items whose captured tags overlap any of {@code tags}, newest first (TK-258).
     * Returns empty for an empty tag set without touching the database.
     */
    @Transactional(readOnly = true)
    public List<PendingItem> findByTags(final UUID userId, final List<String> tags) {
        if (tags.isEmpty()) {
            return List.of();
        }
        return pendingItemRepository.findByUserIdAndTagsOverlapping(userId, tags.toArray(String[]::new));
    }

    /** A user's pending items, newest first — the pending screen (TK-257). */
    @Transactional(readOnly = true)
    public List<PendingItem> findAll(final UUID userId) {
        return pendingItemRepository.findByUserIdOrderByDeferredAtDesc(userId);
    }

    /**
     * Restores a pending item back into active use: creates a title-only {@link Task} in
     * {@code targetListId} and removes the item from the bucket (TK-257/258). Throws if the pending item
     * or the target list no longer exists. Use {@link #delete} to discard without restoring.
     */
    @Transactional
    public Task restore(final UUID pendingItemId, final UUID targetListId) {
        final PendingItem item = pendingItemRepository.findById(pendingItemId)
                .orElseThrow(() -> new IllegalArgumentException("Pending item not found: " + pendingItemId));
        final TaskList target = listRepository.findById(targetListId)
                .orElseThrow(() -> new IllegalArgumentException("List not found: " + targetListId));
        final Task task = taskRepository.save(Task.create(target, item.getTitle()));
        pendingItemRepository.delete(item);
        log.info("Restored pending item {} into list {} as task {}", pendingItemId, targetListId, task.getId());
        return task;
    }

    /** Discards a pending item without restoring it — the "drop" path (TK-257). */
    @Transactional
    public void delete(final UUID pendingItemId) {
        pendingItemRepository.deleteById(pendingItemId);
    }
}
