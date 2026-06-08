package io.tykalo.nudger;

/**
 * Lifecycle state of a {@link Nudger}. Stored as {@code VARCHAR} and constrained by the
 * {@code status} CHECK in {@code V8__nudgers_tables.sql}; the names here must match that list.
 *
 * <ul>
 *   <li>{@code PENDING} — invited, awaiting the invitee's consent (TK-153).</li>
 *   <li>{@code ACTIVE} — consented; escalations may be delivered to them.</li>
 *   <li>{@code PAUSED} — temporarily deactivated without removal (TK-154).</li>
 *   <li>{@code REJECTED} — the invitee declined the invite (TK-153); no escalations are sent.</li>
 * </ul>
 */
public enum NudgerStatus {
    PENDING,
    ACTIVE,
    PAUSED,
    REJECTED
}
