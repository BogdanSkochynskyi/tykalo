package io.tykalo.nudger;

import io.tykalo.user.User;

/**
 * Outcome of an owner managing one existing pairing by {@code @username} (TK-154) — the
 * {@code pause}/{@code resume}/{@code remove} subcommands of {@code /nudgers}. The lookup is shared,
 * so the same four cases cover all three operations.
 *
 * <ul>
 *   <li>{@link Ok} — the pairing was found and the operation changed it (paused, resumed, removed).</li>
 *   <li>{@link Unchanged} — the pairing exists but the operation does not apply to its current status
 *       (e.g. pausing an already-paused nudger); nothing was written.</li>
 *   <li>{@link NotANudger} — that user exists but is not one of this owner's nudgers.</li>
 *   <li>{@link NotRegistered} — no user has that username.</li>
 * </ul>
 */
public sealed interface NudgerActionResult {

    record Ok(Nudger nudger, User invitee) implements NudgerActionResult {
    }

    record Unchanged(Nudger nudger, User invitee) implements NudgerActionResult {
    }

    record NotANudger(String username) implements NudgerActionResult {
    }

    record NotRegistered(String username) implements NudgerActionResult {
    }
}
