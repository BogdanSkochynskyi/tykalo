package io.tykalo.nudger;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EscalationPolicyRepository extends JpaRepository<EscalationPolicy, UUID> {

    /** A target's escalation ladder, lowest level first — the order the cron walks it. */
    List<EscalationPolicy> findByTargetTypeAndTargetIdOrderByLevelAsc(EscalationTargetType targetType, UUID targetId);

    /**
     * The escalation rungs of every given target, lowest level first — the escalation cron's batch
     * load. Callers group the result by {@code targetId}; within each group levels stay ascending.
     */
    List<EscalationPolicy> findByTargetTypeAndTargetIdInOrderByLevelAsc(
            EscalationTargetType targetType, Collection<UUID> targetIds);
}
