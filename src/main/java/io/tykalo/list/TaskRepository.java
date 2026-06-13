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

    /** Live (non-archived) tasks in a list, oldest first — the order they render in the message. */
    List<Task> findByListIdAndArchivedAtIsNullOrderByCreatedAtAsc(UUID listId);

    long countByListIdAndArchivedAtIsNull(UUID listId);

    long countByListIdAndStatusAndArchivedAtIsNull(UUID listId, TaskStatus status);

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

    /**
     * Still-actionable {@code PROJECT}-list tasks past their due date — the overdue-reminder cron's
     * input. {@code TODO}, {@code dueAt < now}, not archived, joined to a live {@link TaskList} of
     * type {@code PROJECT}. Ordered by due time so the oldest overdue task is processed first.
     */
    @Query("""
            SELECT t FROM Task t, TaskList l
            WHERE t.listId = l.id
              AND l.type = io.tykalo.list.ListType.PROJECT
              AND l.archivedAt IS NULL
              AND t.status = io.tykalo.list.TaskStatus.TODO
              AND t.archivedAt IS NULL
              AND t.dueAt < :now
            ORDER BY t.dueAt ASC
            """)
    List<Task> findOverdueProjectTasks(@Param("now") Instant now);

    /**
     * An owner's still-actionable {@code PROJECT}-list tasks due within the half-open window
     * {@code [startInclusive, endExclusive)} — the morning digest's content. Joins to a live
     * {@link TaskList} of type {@code PROJECT}; results are ordered by due time then title so the
     * digest body and its inline buttons share one numbering.
     */
    @Query("""
            SELECT t FROM Task t, TaskList l
            WHERE t.listId = l.id
              AND l.type = io.tykalo.list.ListType.PROJECT
              AND l.archivedAt IS NULL
              AND t.ownerId = :ownerId
              AND t.status = io.tykalo.list.TaskStatus.TODO
              AND t.archivedAt IS NULL
              AND t.dueAt >= :startInclusive
              AND t.dueAt < :endExclusive
            ORDER BY t.dueAt ASC, t.title ASC
            """)
    List<Task> findProjectTasksDueBetween(@Param("ownerId") UUID ownerId,
                                          @Param("startInclusive") Instant startInclusive,
                                          @Param("endExclusive") Instant endExclusive);
}
