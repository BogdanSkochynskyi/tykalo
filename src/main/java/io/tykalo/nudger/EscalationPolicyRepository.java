package io.tykalo.nudger;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EscalationPolicyRepository extends JpaRepository<EscalationPolicy, UUID> {

    /** A target's escalation ladder, lowest level first — the order the cron walks it. */
    List<EscalationPolicy> findByTargetTypeAndTargetIdOrderByLevelAsc(EscalationTargetType targetType, UUID targetId);
}
