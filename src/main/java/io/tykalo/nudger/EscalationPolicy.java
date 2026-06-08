package io.tykalo.nudger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.jspecify.annotations.Nullable;

/**
 * One rung of the escalation ladder for a single target (a {@code TASK} or a whole {@code LIST}),
 * mapped to the {@code escalation_policies} table (Flyway {@code V8__nudgers_tables.sql}). After
 * {@code delayMinutes} past the due time the level fires, revealing {@code revealFields} of the task.
 * {@code targetId} is polymorphic, so it is a raw UUID with no foreign key. Default ladders are
 * created per Project task in TK-155.
 */
@Entity
@Table(name = "escalation_policies")
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(of = "id")
public class EscalationPolicy {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private @Nullable UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 4)
    private EscalationTargetType targetType;

    @Column(name = "target_id", nullable = false)
    private UUID targetId;

    @Column(name = "level", nullable = false)
    private int level;

    @Column(name = "delay_minutes", nullable = false)
    private int delayMinutes;

    @Enumerated(EnumType.STRING)
    @Column(name = "reveal_fields", nullable = false, length = 11)
    private RevealField revealFields;

    /** Builds a single escalation rung for {@code targetType}/{@code targetId}. */
    public static EscalationPolicy of(final EscalationTargetType targetType, final UUID targetId,
                                      final int level, final int delayMinutes, final RevealField revealFields) {
        final EscalationPolicy policy = new EscalationPolicy();
        policy.targetType = targetType;
        policy.targetId = targetId;
        policy.level = level;
        policy.delayMinutes = delayMinutes;
        policy.revealFields = revealFields;
        return policy;
    }
}
