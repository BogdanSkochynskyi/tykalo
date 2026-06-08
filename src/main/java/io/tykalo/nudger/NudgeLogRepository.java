package io.tykalo.nudger;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NudgeLogRepository extends JpaRepository<NudgeLog, UUID> {

    /** Every escalation logged against a target — used to tell which levels have already been sent. */
    List<NudgeLog> findByTargetTypeAndTargetId(EscalationTargetType targetType, UUID targetId);

    /**
     * Every escalation logged against any of the given targets — the escalation cron's batch lookup
     * for which (nudger, level) pairs have already been delivered, so it never re-sends.
     */
    List<NudgeLog> findByTargetTypeAndTargetIdIn(EscalationTargetType targetType, Collection<UUID> targetIds);

    /**
     * How many escalations this nudger has acknowledged since {@code since} — the count behind the
     * owner's "X reminded you N times this month" notice (TK-157). Since a {@code nudge_log} row belongs
     * to exactly one (owner, nudger) pairing, counting by {@code nudgerId} already scopes it to one owner.
     */
    long countByNudgerIdAndAcknowledgedAtGreaterThanEqual(UUID nudgerId, Instant since);

    /**
     * How many escalations this nudger has been <em>sent</em> since {@code since} — the anti-fatigue
     * cap (TK-159) counts this against the owner's {@code nudger_daily_limit} to decide whether the
     * nudger may receive another reminder today. A {@code nudge_log} row belongs to exactly one
     * (owner, nudger) pairing, so counting by {@code nudgerId} already scopes it to one owner.
     */
    long countByNudgerIdAndSentAtGreaterThanEqual(UUID nudgerId, Instant since);
}
