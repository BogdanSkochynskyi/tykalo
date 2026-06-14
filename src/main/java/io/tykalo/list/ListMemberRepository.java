package io.tykalo.list;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ListMemberRepository extends JpaRepository<ListMember, UUID> {

    /** Every member of a list (one row per user). */
    List<ListMember> findByListId(UUID listId);

    /** Every membership a user holds (one row per list they belong to). */
    List<ListMember> findByUserId(UUID userId);

    /** The user's membership on a specific list, if any — the building block for permission checks. */
    Optional<ListMember> findByListIdAndUserId(UUID listId, UUID userId);

    /** A user's memberships filtered to a role (e.g. all lists they OWN). */
    List<ListMember> findByUserIdAndRole(UUID userId, ListMemberRole role);

    /** A user's memberships in a given lifecycle status (e.g. all lists where they are ACTIVE). */
    List<ListMember> findByUserIdAndStatus(UUID userId, ListMemberStatus status);

    /** Whether the user is already a member of the list. */
    boolean existsByListIdAndUserId(UUID listId, UUID userId);
}
