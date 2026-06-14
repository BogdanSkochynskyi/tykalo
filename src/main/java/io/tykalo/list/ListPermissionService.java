package io.tykalo.list;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The permission boundary for shared lists (TK-192). Every mutation that {@link ListService} and
 * {@link TaskService} expose to an acting user goes through one of the {@code requireCan*} guards
 * here; the {@code can*} predicates are the read-only counterparts used by the UI to decide which
 * buttons to show.
 *
 * <p>A user's effective role on a list is resolved by {@link #resolveRole}: an explicit
 * {@link ListMember} row wins, and otherwise — for lists that have no member rows yet (private lists
 * predating the TK-197 backfill) — {@code lists.owner_id} stays the authority and grants
 * {@link ListMemberRole#OWNER}. A user with neither has no access.
 */
@Service
@RequiredArgsConstructor
public class ListPermissionService {

    private static final Set<ListMemberRole> ANY_ROLE = EnumSet.allOf(ListMemberRole.class);
    private static final Set<ListMemberRole> EDITORS = EnumSet.of(ListMemberRole.OWNER, ListMemberRole.EDITOR);
    private static final Set<ListMemberRole> OWNERS = EnumSet.of(ListMemberRole.OWNER);

    private final ListMemberRepository listMemberRepository;
    private final ListRepository listRepository;

    /** Whether the user may see the list — any role (OWNER, EDITOR or MEMBER). */
    @Transactional(readOnly = true)
    public boolean canView(final UUID userId, final UUID listId) {
        return hasRole(userId, listId, ANY_ROLE);
    }

    /** Whether the user may add items — any role. */
    @Transactional(readOnly = true)
    public boolean canAddItems(final UUID userId, final UUID listId) {
        return hasRole(userId, listId, ANY_ROLE);
    }

    /** Whether the user may toggle items done/undone — any role, on any item (not just their own). */
    @Transactional(readOnly = true)
    public boolean canToggleItems(final UUID userId, final UUID listId) {
        return hasRole(userId, listId, ANY_ROLE);
    }

    /** Whether the user may edit the list itself (rename, change type, archive) — OWNER or EDITOR. */
    @Transactional(readOnly = true)
    public boolean canEditList(final UUID userId, final UUID listId) {
        return hasRole(userId, listId, EDITORS);
    }

    /** Whether the user may manage members (invite/remove, change roles except OWNER) — OWNER or EDITOR. */
    @Transactional(readOnly = true)
    public boolean canManageMembers(final UUID userId, final UUID listId) {
        return hasRole(userId, listId, EDITORS);
    }

    /** Whether the user may delete the list — OWNER only. */
    @Transactional(readOnly = true)
    public boolean canDeleteList(final UUID userId, final UUID listId) {
        return hasRole(userId, listId, OWNERS);
    }

    /** Whether the user may transfer ownership — OWNER only. */
    @Transactional(readOnly = true)
    public boolean canTransferOwnership(final UUID userId, final UUID listId) {
        return hasRole(userId, listId, OWNERS);
    }

    /** Asserts {@link #canAddItems}; throws {@link ListPermissionDeniedException} otherwise. */
    @Transactional(readOnly = true)
    public void requireCanAddItems(final UUID userId, final UUID listId) {
        require(canAddItems(userId, listId), userId, listId, "add items");
    }

    /** Asserts {@link #canToggleItems}; throws {@link ListPermissionDeniedException} otherwise. */
    @Transactional(readOnly = true)
    public void requireCanToggleItems(final UUID userId, final UUID listId) {
        require(canToggleItems(userId, listId), userId, listId, "toggle items");
    }

    /** Asserts {@link #canEditList}; throws {@link ListPermissionDeniedException} otherwise. */
    @Transactional(readOnly = true)
    public void requireCanEditList(final UUID userId, final UUID listId) {
        require(canEditList(userId, listId), userId, listId, "edit list");
    }

    /** Asserts {@link #canDeleteList}; throws {@link ListPermissionDeniedException} otherwise. */
    @Transactional(readOnly = true)
    public void requireCanDeleteList(final UUID userId, final UUID listId) {
        require(canDeleteList(userId, listId), userId, listId, "delete list");
    }

    /** Asserts {@link #canManageMembers}; throws {@link ListPermissionDeniedException} otherwise. */
    @Transactional(readOnly = true)
    public void requireCanManageMembers(final UUID userId, final UUID listId) {
        require(canManageMembers(userId, listId), userId, listId, "manage members");
    }

    /**
     * The user's effective role on the list: an explicit {@link ListMemberStatus#ACTIVE}
     * {@link ListMember} row if present, else {@link ListMemberRole#OWNER} when they are the list's
     * {@code owner_id} (legacy authority for pre-backfill private lists), else empty. A
     * {@link ListMemberStatus#PENDING} invite grants nothing — the invitee has no access until they
     * accept (TK-193).
     */
    @Transactional(readOnly = true)
    public Optional<ListMemberRole> resolveRole(final UUID userId, final UUID listId) {
        final Optional<ListMember> member = listMemberRepository.findByListIdAndUserId(listId, userId)
                .filter(m -> m.getStatus() == ListMemberStatus.ACTIVE);
        if (member.isPresent()) {
            return member.map(ListMember::getRole);
        }
        return listRepository.findById(listId)
                .filter(list -> userId.equals(list.getOwnerId()))
                .map(list -> ListMemberRole.OWNER);
    }

    private boolean hasRole(final UUID userId, final UUID listId, final Set<ListMemberRole> allowed) {
        return resolveRole(userId, listId).map(allowed::contains).orElse(false);
    }

    private void require(final boolean allowed, final UUID userId, final UUID listId, final String action) {
        if (!allowed) {
            throw new ListPermissionDeniedException(userId, listId, action, resolveRole(userId, listId).orElse(null));
        }
    }
}
