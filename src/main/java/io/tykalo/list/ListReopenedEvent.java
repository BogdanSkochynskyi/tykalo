package io.tykalo.list;

import java.util.UUID;

/**
 * Signals that a list transitioned {@code COMPLETED -> ACTIVE} via {@link ListLifecycleService#reopen}
 * (TK-252) — the user changed their mind after closing it. Restoring an {@code ARCHIVED} list is a
 * separate operation (TK-260) and does not fire this event.
 *
 * @param listId  the list that was reopened
 * @param actorId the user who reopened it
 */
public record ListReopenedEvent(UUID listId, UUID actorId) {
}
