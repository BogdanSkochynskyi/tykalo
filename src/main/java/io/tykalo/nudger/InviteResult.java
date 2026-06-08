package io.tykalo.nudger;

import io.tykalo.user.User;

/**
 * Outcome of {@link NudgerService#invite(User, String)} — an owner running {@code /nudgers add}.
 *
 * <ul>
 *   <li>{@link Invited} — the invitee is registered; a {@code PENDING} {@link Nudger} was created.</li>
 *   <li>{@link AlreadyInvited} — a pairing for this owner/invitee already exists (any status).</li>
 *   <li>{@link SelfInvite} — the owner tried to add themselves.</li>
 *   <li>{@link NotRegistered} — no user has that username; the caller offers a deep-link instead.</li>
 * </ul>
 */
public sealed interface InviteResult {

    record Invited(Nudger nudger, User invitee) implements InviteResult {
    }

    record AlreadyInvited(Nudger nudger, User invitee) implements InviteResult {
    }

    record SelfInvite() implements InviteResult {
    }

    record NotRegistered(String username) implements InviteResult {
    }
}
