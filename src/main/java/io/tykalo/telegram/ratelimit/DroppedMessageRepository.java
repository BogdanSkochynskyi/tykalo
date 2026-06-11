package io.tykalo.telegram.ratelimit;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DroppedMessageRepository extends JpaRepository<DroppedMessage, UUID> {
}
