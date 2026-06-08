package io.tykalo.scheduling;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReminderLogRepository extends JpaRepository<ReminderLog, UUID> {

    /** All reminder-log rows for the given tasks — used to batch-resolve each task's max sent level. */
    List<ReminderLog> findByTaskIdIn(Collection<UUID> taskIds);
}
