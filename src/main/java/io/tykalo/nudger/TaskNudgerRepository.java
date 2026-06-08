package io.tykalo.nudger;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface TaskNudgerRepository extends JpaRepository<TaskNudger, UUID> {

    /** The Nudgers pinned to a single task (TK-158 {@code /task <id> nudgers}). */
    List<TaskNudger> findByTaskId(UUID taskId);

    /** The assignments of several tasks at once — the escalation cron's batch load. */
    List<TaskNudger> findByTaskIdIn(Collection<UUID> taskIds);

    /** Guards against inserting a duplicate (task, nudger) link before the unique constraint would. */
    boolean existsByTaskIdAndNudgerId(UUID taskId, UUID nudgerId);

    /** Clears a task's assignment, e.g. when replacing it or switching the task to private. */
    @Transactional
    void deleteByTaskId(UUID taskId);
}
