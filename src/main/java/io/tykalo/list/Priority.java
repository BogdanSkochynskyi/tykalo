package io.tykalo.list;

/**
 * Optional importance of a {@link Task}. Stored as {@code VARCHAR} and constrained by the
 * {@code priority} CHECK in {@code V3__tasks_table.sql}; the names here must match that list.
 */
public enum Priority {
    LOW,
    MEDIUM,
    HIGH,
    URGENT
}
