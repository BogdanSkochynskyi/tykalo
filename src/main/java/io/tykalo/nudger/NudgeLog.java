package io.tykalo.nudger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.jspecify.annotations.Nullable;

/**
 * A record that an escalation of a given {@code level} was delivered to a nudger for a target, mapped
 * to the {@code nudge_log} table (Flyway {@code V8__nudgers_tables.sql}); time is stored in UTC. The
 * escalation cron (TK-156) writes one row per level actually sent; {@code acknowledgedAt} is stamped
 * when the nudger taps "I reminded" (TK-157). {@code targetId} is polymorphic (no FK); {@code nudgerId}
 * references {@link Nudger}.
 *
 * <p>The id is <b>app-assigned</b> in {@link #of} rather than DB/Hibernate-generated: the escalation
 * cron needs the id while building the "✅ I reminded" inline button <em>before</em> the row is saved
 * (so a thrown send still leaves no row and is retried), and the button's {@code callback_data} carries
 * this id back for acknowledgement.
 */
@Entity
@Table(name = "nudge_log")
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(of = "id")
public class NudgeLog {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 4)
    private EscalationTargetType targetType;

    @Column(name = "target_id", nullable = false, updatable = false)
    private UUID targetId;

    @Column(name = "nudger_id", nullable = false, updatable = false)
    private UUID nudgerId;

    @Column(name = "level", nullable = false)
    private int level;

    @Column(name = "sent_at", nullable = false, updatable = false)
    private Instant sentAt;

    @Column(name = "acknowledged_at")
    private @Nullable Instant acknowledgedAt;

    @Column(name = "message_template")
    private @Nullable String messageTemplate;

    public Optional<Instant> getAcknowledgedAt() {
        return Optional.ofNullable(acknowledgedAt);
    }

    public Optional<String> getMessageTemplate() {
        return Optional.ofNullable(messageTemplate);
    }

    /**
     * Builds a log entry recording that {@code level} was delivered to {@code nudgerId} for the given
     * target at {@code sentAt}, carrying the rendered {@code messageTemplate} for audit. The entry
     * starts un-acknowledged.
     */
    public static NudgeLog of(final EscalationTargetType targetType, final UUID targetId, final UUID nudgerId,
                              final int level, final Instant sentAt, final @Nullable String messageTemplate) {
        final NudgeLog log = new NudgeLog();
        log.id = UUID.randomUUID();
        log.targetType = targetType;
        log.targetId = targetId;
        log.nudgerId = nudgerId;
        log.level = level;
        log.sentAt = sentAt;
        log.messageTemplate = messageTemplate;
        return log;
    }
}
