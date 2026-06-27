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
 * The lifecycle state machine for {@link TaskList} (TK-252). Lists move through
 * {@code ACTIVE -> COMPLETED -> ARCHIVED} via explicit, attributable transitions; there are no implicit
 * jumps and no way back without an explicit {@link #reopen} (a {@code COMPLETED -> ACTIVE} undo).
 * Restoring an {@code ARCHIVED} list back to {@code ACTIVE} is a separate operation handled by TK-260.
 *
 * <p>Every transition enforces the OWNER/EDITOR permission at the boundary via
 * {@link ListPermissionService#requireCanEditList} (TK-192) and publishes a domain event so downstream
 * features (auto-close TK-253, close flow TK-254, archive view TK-260) can react without polling.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ListLifecycleService {

    private final ListRepository listRepository;
    private final ListPermissionService permissionService;
    private final ApplicationEventPublisher eventPublisher;

    /** {@code ACTIVE -> COMPLETED}, stamping {@code closedAt}. Throws if the list is not currently ACTIVE. */
    @Transactional
    public TaskList markCompleted(final UUID actorId, final UUID listId) {
        permissionService.requireCanEditList(actorId, listId);
        final TaskList list = require(listId);
        requireStatus(list, ListStatus.ACTIVE, "complete");
        list.setStatus(ListStatus.COMPLETED);
        list.setClosedAt(Instant.now());
        log.info("List {} completed by {}", listId, actorId);
        eventPublisher.publishEvent(new ListCompletedEvent(listId, actorId));
        return list;
    }

    /**
     * {@code COMPLETED -> ARCHIVED}, stamping {@code archivedAt} (and preserving the {@code closedAt} set
     * when it was completed). A list must be closed before it can be archived; throws otherwise.
     */
    @Transactional
    public TaskList markArchived(final UUID actorId, final UUID listId) {
        permissionService.requireCanEditList(actorId, listId);
        final TaskList list = require(listId);
        requireStatus(list, ListStatus.COMPLETED, "archive");
        list.setStatus(ListStatus.ARCHIVED);
        list.setArchivedAt(Instant.now());
        log.info("List {} archived by {}", listId, actorId);
        eventPublisher.publishEvent(new ListArchivedEvent(listId, actorId));
        return list;
    }

    /** {@code COMPLETED -> ACTIVE}, clearing {@code closedAt}. Throws if the list is not currently COMPLETED. */
    @Transactional
    public TaskList reopen(final UUID actorId, final UUID listId) {
        permissionService.requireCanEditList(actorId, listId);
        final TaskList list = require(listId);
        requireStatus(list, ListStatus.COMPLETED, "reopen");
        list.setStatus(ListStatus.ACTIVE);
        list.setClosedAt(null);
        log.info("List {} reopened by {}", listId, actorId);
        eventPublisher.publishEvent(new ListReopenedEvent(listId, actorId));
        return list;
    }

    /** Active, non-deleted lists for the owner — the main lists screen. */
    @Transactional(readOnly = true)
    public List<TaskList> findActiveByOwner(final UUID ownerId) {
        return listRepository.findByOwnerIdAndStatusAndArchivedAtIsNull(ownerId, ListStatus.ACTIVE);
    }

    /** Archived lists for the owner closed within {@code [from, to]} — the archive screen (TK-260). */
    @Transactional(readOnly = true)
    public List<TaskList> findArchivedByOwner(final UUID ownerId, final Instant from, final Instant to) {
        return listRepository.findByOwnerIdAndStatusAndClosedAtBetween(ownerId, ListStatus.ARCHIVED, from, to);
    }

    private TaskList require(final UUID listId) {
        return listRepository.findById(listId)
                .orElseThrow(() -> new IllegalArgumentException("List not found: " + listId));
    }

    private void requireStatus(final TaskList list, final ListStatus expected, final String action) {
        if (list.getStatus() != expected) {
            throw new IllegalStateException("Cannot %s list %s: expected status %s but was %s"
                    .formatted(action, list.getId(), expected, list.getStatus()));
        }
    }
}
