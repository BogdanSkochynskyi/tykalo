package io.tykalo.nudger;

/**
 * What an {@link EscalationPolicy} / {@link NudgeLog} row points at. Stored as {@code VARCHAR} and
 * constrained by the {@code target_type} CHECK in {@code V8__nudgers_tables.sql}; the names here must
 * match that list. The target id is polymorphic, so it carries no foreign key.
 */
public enum EscalationTargetType {
    TASK,
    LIST
}
