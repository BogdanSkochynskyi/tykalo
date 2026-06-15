package io.tykalo.list;

import io.tykalo.user.User;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Outcome of {@link ListMemberService#transferOwnership} — the OWNER handing the list to another active
 * member (TK-194). On success the previous owner is demoted to EDITOR, the target becomes OWNER and
 * {@code lists.owner_id} is reassigned so it stays the authority source.
 *
 * <ul>
 *   <li>{@link Transferred} — ownership moved; the new owner (and, when known, the previous owner)
 *       should be notified.</li>
 *   <li>{@link Denied} — the acting user is not the current OWNER.</li>
 *   <li>{@link InvalidTarget} — the target membership is missing, not active, or already the OWNER.</li>
 * </ul>
 */
public sealed interface TransferOwnershipResult {

    record Transferred(TaskList list, @Nullable User previousOwner, User newOwner)
            implements TransferOwnershipResult {
        public Optional<User> previousOwnerOpt() {
            return Optional.ofNullable(previousOwner);
        }
    }

    record Denied() implements TransferOwnershipResult {
    }

    record InvalidTarget() implements TransferOwnershipResult {
    }
}
