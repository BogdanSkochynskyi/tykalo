package io.tykalo.nudger;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NudgerRepository extends JpaRepository<Nudger, UUID> {

    /** All nudger pairings an owner has invited, in any status. */
    List<Nudger> findByOwnerId(UUID ownerId);

    /** An owner's nudger pairings in a given status — e.g. the ACTIVE ones the escalation cron uses. */
    List<Nudger> findByOwnerIdAndStatus(UUID ownerId, NudgerStatus status);

    /**
     * The pairings of several owners in a given status — the escalation cron's batch load of every
     * overdue task owner's ACTIVE nudgers in one query. Callers group the result by {@code ownerId}.
     */
    List<Nudger> findByOwnerIdInAndStatus(Collection<UUID> ownerIds, NudgerStatus status);

    /** The single pairing for an (owner, nudger user), used to deduplicate invites (TK-152). */
    Optional<Nudger> findByOwnerIdAndNudgerUserId(UUID ownerId, UUID nudgerUserId);
}
