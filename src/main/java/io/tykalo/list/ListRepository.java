package io.tykalo.list;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ListRepository extends JpaRepository<TaskList, UUID> {

    List<TaskList> findByOwnerId(UUID ownerId);

    List<TaskList> findByOwnerIdAndArchivedAtIsNull(UUID ownerId);
}
