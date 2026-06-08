package io.tykalo.nudger;

import io.tykalo.user.User;

/**
 * Outcome of {@link NudgerService#acceptViaDeepLink(User, java.util.UUID)} — a freshly registered
 * user arriving through an invite deep-link.
 *
 * <ul>
 *   <li>{@link Invited} — a {@code PENDING} {@link Nudger} was created for the decoded owner.</li>
 *   <li>{@link AlreadyInvited} — that pairing already exists (the link was reused).</li>
 *   <li>{@link SelfInvite} — the clicker is the owner encoded in the link.</li>
 *   <li>{@link OwnerGone} — the link decoded, but no user has that owner id anymore.</li>
 * </ul>
 */
public sealed interface AcceptResult {

    record Invited(Nudger nudger, User owner) implements AcceptResult {
    }

    record AlreadyInvited(Nudger nudger, User owner) implements AcceptResult {
    }

    record SelfInvite() implements AcceptResult {
    }

    record OwnerGone() implements AcceptResult {
    }
}
