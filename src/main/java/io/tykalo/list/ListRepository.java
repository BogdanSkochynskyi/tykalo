package io.tykalo.list;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ListRepository extends JpaRepository<TaskList, UUID> {

    List<TaskList> findByOwnerId(UUID ownerId);

    List<TaskList> findByOwnerIdAndArchivedAtIsNull(UUID ownerId);

    List<TaskList> findByOwnerIdAndTypeAndArchivedAtIsNull(UUID ownerId, ListType type);

    /**
     * Lists in the given lifecycle status for the owner, excluding soft-deleted rows (TK-252). The
     * extra {@code archivedAt IS NULL} guard matters for {@code ACTIVE}: {@link ListService#deleteList}
     * soft-deletes by stamping {@code archivedAt} without flipping {@code status}, so an ACTIVE-status
     * but soft-deleted list must not surface on the main screen.
     */
    List<TaskList> findByOwnerIdAndStatusAndArchivedAtIsNull(UUID ownerId, ListStatus status);

    /** Lists for the owner in the given status whose {@code closedAt} falls in {@code [from, to]} — backs the archive screen (TK-260). */
    List<TaskList> findByOwnerIdAndStatusAndClosedAtBetween(UUID ownerId, ListStatus status, Instant from, Instant to);

    /** All lists in the given status archived after {@code after} — for retrospective queries (TK-252 acceptance). */
    List<TaskList> findByStatusAndArchivedAtAfter(ListStatus status, Instant after);
}
