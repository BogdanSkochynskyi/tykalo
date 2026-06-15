package io.tykalo.list;

import io.tykalo.user.User;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Outcome of {@link ListMemberService#remove} — an OWNER/EDITOR removing a member from a shared list
 * (TK-194).
 *
 * <ul>
 *   <li>{@link Removed} — the membership row was deleted; the {@code removedUser} (when still known)
 *       should be notified.</li>
 *   <li>{@link CannotRemoveOwner} — the target is the list OWNER, who must transfer ownership first.</li>
 *   <li>{@link Denied} — the acting user may not manage this list's members.</li>
 *   <li>{@link NotFound} — no membership with that id (unknown, or already removed).</li>
 * </ul>
 */
public sealed interface RemoveMemberResult {

    record Removed(TaskList list, @Nullable User removedUser) implements RemoveMemberResult {
        public Optional<User> removedUserOpt() {
            return Optional.ofNullable(removedUser);
        }
    }

    record CannotRemoveOwner() implements RemoveMemberResult {
    }

    record Denied() implements RemoveMemberResult {
    }

    record NotFound() implements RemoveMemberResult {
    }
}
