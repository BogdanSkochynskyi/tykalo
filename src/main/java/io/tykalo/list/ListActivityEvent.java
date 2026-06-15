package io.tykalo.list;

import java.util.UUID;

/**
 * A user-initiated, attributable change to a list — items added or completed by a known actor (TK-196).
 * Unlike {@link ListChangedEvent} (which only says "this list needs re-rendering" and fires from every
 * mutator, including system/recurrence paths), this carries <em>who</em> did <em>what</em> and is fired
 * only from the actor-aware {@link TaskService} entry points. That is exactly what the shared-list
 * notification aggregator needs to build "@anna added 3 items" and to skip notifying the actor.
 *
 * <p>Deliberately separate from {@link ListChangedEvent}: live UI sync and push notifications have
 * different triggers (sync on every change, push only on genuine user actions) and different listeners.
 *
 * @param listId  the list that changed
 * @param actorId the user who made the change
 * @param kind    what kind of change it was
 * @param count   how many items the change covered (e.g. 3 items added)
 */
public record ListActivityEvent(UUID listId, UUID actorId, Kind kind, int count) {

    /** The attributable kinds of list activity the aggregator reports. */
    public enum Kind {
        ADDED,
        COMPLETED
    }
}
