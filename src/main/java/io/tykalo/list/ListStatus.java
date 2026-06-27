package io.tykalo.list;

/**
 * Lifecycle state of a {@link TaskList} (TK-251). Lists move through an explicit state machine
 * {@code ACTIVE -> COMPLETED -> ARCHIVED}, with carry-over support for unfinished items. The
 * transitions and their permission rules live in the service layer (TK-252); this enum only names
 * the states and backs the {@code lists.status} column.
 */
public enum ListStatus {

    /** Default state — the list is in use, editable, items can be added and toggled. */
    ACTIVE,

    /** User (or auto-close) explicitly closed the list. Read-only for items, can be reopened to ACTIVE. */
    COMPLETED,

    /** Pushed to long-term storage, hidden from the main list view. Can be restored to ACTIVE. */
    ARCHIVED
}
