package io.tykalo.list;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TaskRepository extends JpaRepository<Task, UUID> {

    List<Task> findByOwnerIdAndStatus(UUID ownerId, TaskStatus status);

    List<Task> findByListId(UUID listId);

    long countByListIdAndArchivedAtIsNull(UUID listId);

    /**
     * Still-actionable tasks past their due date: {@code TODO}, due before {@code now}, not archived.
     */
    @Query("""
            SELECT t FROM Task t
            WHERE t.status = io.tykalo.list.TaskStatus.TODO
              AND t.dueAt < :now
              AND t.archivedAt IS NULL
            """)
    List<Task> findOverdue(@Param("now") Instant now);

    /**
     * An owner's still-actionable overdue tasks: {@code TODO}, due before {@code now}, not archived.
     */
    @Query("""
            SELECT t FROM Task t
            WHERE t.ownerId = :ownerId
              AND t.status = io.tykalo.list.TaskStatus.TODO
              AND t.dueAt < :now
              AND t.archivedAt IS NULL
            """)
    List<Task> findOverdueByOwner(@Param("ownerId") UUID ownerId, @Param("now") Instant now);

    /**
     * An owner's still-actionable tasks due within the half-open window {@code [startInclusive,
     * endExclusive)}: {@code TODO}, not archived. Callers compute the window in the user's zone.
     */
    @Query("""
            SELECT t FROM Task t
            WHERE t.ownerId = :ownerId
              AND t.status = io.tykalo.list.TaskStatus.TODO
              AND t.archivedAt IS NULL
              AND t.dueAt >= :startInclusive
              AND t.dueAt < :endExclusive
            """)
    List<Task> findDueBetween(@Param("ownerId") UUID ownerId,
                              @Param("startInclusive") Instant startInclusive,
                              @Param("endExclusive") Instant endExclusive);
}
