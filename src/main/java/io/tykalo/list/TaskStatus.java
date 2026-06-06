package io.tykalo.list;

/**
 * Lifecycle state of a {@link Task}. Stored as {@code VARCHAR} and constrained by the
 * {@code status} CHECK in {@code V3__tasks_table.sql}; the names here must match that list.
 */
public enum TaskStatus {
    TODO,
    DONE,
    CANCELLED
}
