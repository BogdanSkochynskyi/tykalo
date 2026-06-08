package io.tykalo.nudger;

/**
 * How much of a task a given escalation level discloses to the nudger. Stored as {@code VARCHAR} and
 * constrained by the {@code reveal_fields} CHECK in {@code V8__nudgers_tables.sql}; the names here
 * must match that list. The default ladder is NUMBER → TITLE → DESCRIPTION (TK-155).
 */
public enum RevealField {
    NUMBER,
    TITLE,
    DESCRIPTION
}
