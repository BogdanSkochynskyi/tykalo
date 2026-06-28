package io.tykalo.list;

import java.util.UUID;

/**
 * Published by {@link TaskService} right after a task transitions to {@link TaskStatus#DONE} (via
 * {@code markDone} or {@code completeTask}), once any recurring next-instance has been spawned. It lets
 * the auto-close detector (TK-253) react to "an item just got finished" without {@code list} depending on
 * the {@code menu} layer — the listener reloads the list to decide whether all items are now done.
 *
 * <p>Carries only ids: the {@code list} the item belongs to and the {@code task} that completed (for
 * traceability). Not fired when a repeat {@code markDone} is a no-op (the status did not actually change).
 *
 * @param listId the list the completed task belongs to
 * @param taskId the task that transitioned to DONE
 */
public record TaskCompletedEvent(UUID listId, UUID taskId) {
}
