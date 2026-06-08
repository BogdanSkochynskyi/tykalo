package io.tykalo.nudger;

import io.tykalo.user.User;
import java.util.UUID;

/**
 * Outcome of {@link NudgerService#consent(UUID, boolean)} — an invitee tapping Yes/No on the consent
 * prompt (TK-153).
 *
 * <ul>
 *   <li>{@link Accepted} — a {@code PENDING} pairing was flipped to {@code ACTIVE}; notify the owner.</li>
 *   <li>{@link Declined} — a {@code PENDING} pairing was flipped to {@code REJECTED}; notify the owner.</li>
 *   <li>{@link AlreadyDecided} — the pairing was no longer {@code PENDING} (a replayed tap); no-op, so
 *       the owner is not notified a second time. Carries the pairing in its current state.</li>
 *   <li>{@link NotFound} — no nudger has that id (a stale or forged callback).</li>
 * </ul>
 */
public sealed interface ConsentResult {

    record Accepted(Nudger nudger, User owner) implements ConsentResult {
    }

    record Declined(Nudger nudger, User owner) implements ConsentResult {
    }

    record AlreadyDecided(Nudger nudger) implements ConsentResult {
    }

    record NotFound() implements ConsentResult {
    }
}
