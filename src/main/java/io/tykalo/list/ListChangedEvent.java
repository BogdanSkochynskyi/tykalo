package io.tykalo.list;

import java.util.UUID;

/**
 * Signals that a list's task state changed — a task was added, toggled, edited, snoozed, archived or
 * spawned by recurrence. Published by {@link TaskService} after every mutation so the {@code list}
 * package can refresh the "live" Telegram message(s) mirroring the list (see
 * {@link ListMessageService#onListChanged}) without each handler having to remember to do it.
 *
 * <p>It carries only {@code listId}: the listener does not care <em>what</em> changed, only that the
 * list needs re-rendering, and it re-reads the current tasks itself. This is deliberately separate
 * from {@link TaskCreatedEvent} (which fires only on creation and drives nudger escalation seeding) —
 * different lifecycles, different listeners.
 */
public record ListChangedEvent(UUID listId) {
}
