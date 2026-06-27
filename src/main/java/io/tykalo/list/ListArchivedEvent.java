package io.tykalo.list;

import java.util.UUID;

/**
 * Signals that a list transitioned {@code COMPLETED -> ARCHIVED} via {@link ListLifecycleService#markArchived}
 * (TK-252) — it is pushed to long-term storage and hidden from the main list view.
 *
 * @param listId  the list that was archived
 * @param actorId the user who archived it
 */
public record ListArchivedEvent(UUID listId, UUID actorId) {
}
