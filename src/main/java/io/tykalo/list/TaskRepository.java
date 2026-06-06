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
}
