package io.tykalo.list;

/**
 * Lifecycle state of a {@link Task}. Stored as {@code VARCHAR} and constrained by the
 * {@code status} CHECK in {@code V3__tasks_table.sql}, widened to admit {@code DEFERRED} in
 * {@code V21__tasks_status_deferred.sql}; the names here must match that list.
 *
 * <p>{@code DEFERRED} marks a task the user has "saved for later" (TK-256): it is moved out of its
 * list into the {@code pending_items} bucket and {@code archivedAt} is stamped alongside, so the
 * status records <em>why</em> the task left the list (versus a plain soft-delete or {@code CANCELLED}).
 */
public enum TaskStatus {
    TODO,
    DONE,
    CANCELLED,
    DEFERRED
}
