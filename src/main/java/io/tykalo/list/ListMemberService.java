package io.tykalo.list;

import io.tykalo.user.User;
import io.tykalo.user.UserRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Member management for shared lists (TK-194): listing active members, removing one, and transferring
 * ownership. The complement to {@link ListInviteService} (which adds members); both sit behind the
 * {@link ListPermissionService} boundary. Permission failures return a {@code Denied} result rather than
 * throwing, so the UI-gated callback flow can turn them into a friendly toast.
 *
 * <p>Ownership is dual-tracked: an {@link ListMemberStatus#ACTIVE} OWNER {@link ListMember} row and the
 * legacy {@code lists.owner_id}. A list created through the menu has only the latter (no member rows
 * until someone is invited), so {@link #transferOwnership} keeps both in sync — it demotes the previous
 * owner to EDITOR (creating that row if it didn't exist), promotes the target to OWNER and reassigns
 * {@code owner_id} — preserving the "exactly one OWNER" invariant.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ListMemberService {

    private final ListMemberRepository listMemberRepository;
    private final ListRepository listRepository;
    private final UserRepository userRepository;
    private final ListPermissionService permissionService;

    /** The active members of a list (one row per user), in no particular order. */
    @Transactional(readOnly = true)
    public List<ListMember> activeMembers(final UUID listId) {
        return listMemberRepository.findByListId(listId).stream()
                .filter(member -> member.getStatus() == ListMemberStatus.ACTIVE)
                .toList();
    }

    /**
     * Removes the member identified by {@code memberId} on behalf of {@code actorId}. The actor must be
     * able to manage members (OWNER/EDITOR); the OWNER themselves can never be removed (transfer first).
     */
    @Transactional
    public RemoveMemberResult remove(final UUID actorId, final UUID memberId) {
        final Optional<ListMember> found = listMemberRepository.findById(memberId);
        if (found.isEmpty()) {
            return new RemoveMemberResult.NotFound();
        }
        final ListMember member = found.get();
        if (!permissionService.canManageMembers(actorId, member.getListId())) {
            return new RemoveMemberResult.Denied();
        }
        if (member.getRole() == ListMemberRole.OWNER) {
            return new RemoveMemberResult.CannotRemoveOwner();
        }
        final TaskList list = requireList(member.getListId());
        final User removedUser = userRepository.findById(member.getUserId()).orElse(null);
        listMemberRepository.delete(member);
        log.info("User {} removed member {} (user {}) from list {}",
                actorId, memberId, member.getUserId(), member.getListId());
        return new RemoveMemberResult.Removed(list, removedUser);
    }

    /**
     * Transfers ownership of a list to the active member identified by {@code newOwnerMemberId}. The
     * actor must be the current OWNER and the target an active, non-OWNER member. Demotes the previous
     * owner to EDITOR, promotes the target to OWNER and reassigns {@code lists.owner_id}.
     */
    @Transactional
    public TransferOwnershipResult transferOwnership(final UUID actorId, final UUID newOwnerMemberId) {
        final Optional<ListMember> found = listMemberRepository.findById(newOwnerMemberId);
        if (found.isEmpty()) {
            return new TransferOwnershipResult.InvalidTarget();
        }
        final ListMember target = found.get();
        if (!permissionService.canTransferOwnership(actorId, target.getListId())) {
            return new TransferOwnershipResult.Denied();
        }
        if (target.getStatus() != ListMemberStatus.ACTIVE || target.getRole() == ListMemberRole.OWNER) {
            return new TransferOwnershipResult.InvalidTarget();
        }
        final TaskList list = requireList(target.getListId());
        demotePreviousOwner(actorId, target.getListId());
        target.setRole(ListMemberRole.OWNER);
        listMemberRepository.save(target);
        list.setOwnerId(target.getUserId());
        listRepository.save(list);
        final User newOwner = userRepository.findById(target.getUserId())
                .orElseThrow(() -> new IllegalStateException("New owner user not found: " + target.getUserId()));
        final User previousOwner = userRepository.findById(actorId).orElse(null);
        log.info("User {} transferred ownership of list {} to user {}", actorId, list.getId(), target.getUserId());
        return new TransferOwnershipResult.Transferred(list, previousOwner, newOwner);
    }

    private void demotePreviousOwner(final UUID previousOwnerId, final UUID listId) {
        final ListMember previous = listMemberRepository.findByListIdAndUserId(listId, previousOwnerId)
                .orElseGet(() -> ListMember.of(listId, previousOwnerId, ListMemberRole.EDITOR));
        previous.setRole(ListMemberRole.EDITOR);
        listMemberRepository.save(previous);
    }

    private TaskList requireList(final UUID listId) {
        return listRepository.findById(listId)
                .orElseThrow(() -> new IllegalArgumentException("List not found: " + listId));
    }
}
