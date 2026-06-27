package io.tykalo.list;

import java.util.UUID;

/**
 * Signals that a list transitioned {@code ACTIVE -> COMPLETED} via {@link ListLifecycleService#markCompleted}
 * (TK-252). Downstream listeners — the close-list flow (TK-254), auto-close detection (TK-253) and the
 * archive view (TK-260) — react to this rather than polling list status.
 *
 * @param listId  the list that was completed
 * @param actorId the user who completed it (so self-induced changes can be skipped for notifications)
 */
public record ListCompletedEvent(UUID listId, UUID actorId) {
}
