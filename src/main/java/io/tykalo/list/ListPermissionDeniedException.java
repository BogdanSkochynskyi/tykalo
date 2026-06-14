package io.tykalo.list;

import java.util.UUID;
import lombok.Getter;
import org.jspecify.annotations.Nullable;

/**
 * Raised by {@link ListPermissionService} when a user attempts a list operation their
 * {@link ListMemberRole} does not allow (TK-192). Carries the actor, the target list, the attempted
 * {@code action} (a short human-readable verb phrase, e.g. {@code "delete list"}) and the actor's
 * actual role — {@code null} when they are not a member of the list at all.
 */
@Getter
public class ListPermissionDeniedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final UUID userId;
    private final UUID listId;
    private final String action;
    private final @Nullable ListMemberRole role;

    public ListPermissionDeniedException(final UUID userId, final UUID listId, final String action,
                                         final @Nullable ListMemberRole role) {
        super("User %s (role %s) is not allowed to %s on list %s"
                .formatted(userId, role == null ? "none" : role, action, listId));
        this.userId = userId;
        this.listId = listId;
        this.action = action;
        this.role = role;
    }
}
