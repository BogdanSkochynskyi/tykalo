package io.tykalo.nudger;

import io.tykalo.user.User;
import java.util.UUID;

/**
 * Outcome of {@link NudgeAckService#acknowledge(UUID, java.time.Instant)} — a nudger tapping the
 * "✅ I reminded" button on an escalation message (TK-157).
 *
 * <ul>
 *   <li>{@link Acknowledged} — the escalation was stamped acknowledged and the nudger's karma bumped;
 *       carries the owner to notify, the nudger's display handle, and how many times this nudger has
 *       reminded the owner so far this month.</li>
 *   <li>{@link AlreadyAcknowledged} — the escalation was already acknowledged (a replayed or
 *       double-tapped button); no second karma point, no second owner notice.</li>
 *   <li>{@link NotFound} — no {@code nudge_log} row has that id (a stale button, or the nudger was
 *       removed and the row cascade-deleted).</li>
 * </ul>
 */
public sealed interface AckResult {

    record Acknowledged(User owner, String nudgerHandle, long monthlyCount) implements AckResult {
    }

    record AlreadyAcknowledged() implements AckResult {
    }

    record NotFound() implements AckResult {
    }
}
