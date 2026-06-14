package io.tykalo.list;

import io.tykalo.user.User;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Outcome of {@link ListInviteService#respond} — the invitee's Yes/No answer to a list-invite prompt
 * (TK-193).
 *
 * <ul>
 *   <li>{@link Accepted} — a {@link ListMemberStatus#PENDING} membership was flipped to ACTIVE.</li>
 *   <li>{@link Declined} — the pending membership was deleted; nothing was added.</li>
 *   <li>{@link AlreadyActive} — the membership was already ACTIVE (a replayed accept).</li>
 *   <li>{@link NotFound} — no pending membership with that id (unknown, or already declined/removed).</li>
 * </ul>
 *
 * <p>{@link Accepted} and {@link Declined} carry the {@code inviter} (resolved from
 * {@link ListMember#getInvitedBy()}) so the caller can notify them — it is absent when the inviter is
 * unknown or has since been deleted.
 */
public sealed interface InviteResponseResult {

    record Accepted(ListMember member, TaskList list, @Nullable User inviter) implements InviteResponseResult {
        public Optional<User> inviterOpt() {
            return Optional.ofNullable(inviter);
        }
    }

    record Declined(ListMember member, TaskList list, @Nullable User inviter) implements InviteResponseResult {
        public Optional<User> inviterOpt() {
            return Optional.ofNullable(inviter);
        }
    }

    record AlreadyActive(ListMember member) implements InviteResponseResult {
    }

    record NotFound() implements InviteResponseResult {
    }
}
