package io.tykalo.nudger;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NudgeLogRepository extends JpaRepository<NudgeLog, UUID> {

    /** Every escalation logged against a target — used to tell which levels have already been sent. */
    List<NudgeLog> findByTargetTypeAndTargetId(EscalationTargetType targetType, UUID targetId);
}
