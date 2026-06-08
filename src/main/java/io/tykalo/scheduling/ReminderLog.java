package io.tykalo.scheduling;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.jspecify.annotations.Nullable;

/**
 * A record that a reminder of a given {@link ReminderLevel} was delivered for a task, mapped to the
 * {@code reminder_log} table (Flyway {@code V7__reminder_log_table.sql}). The cron writes one row per
 * level actually sent; the {@code UNIQUE(task_id, level)} constraint guarantees a level is never sent
 * twice. {@code taskId} is held as a raw UUID rather than a {@code @ManyToOne}, matching
 * {@link io.tykalo.list.Task}.
 */
@Entity
@Table(name = "reminder_log")
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(of = "id")
public class ReminderLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private @Nullable UUID id;

    @Column(name = "task_id", nullable = false, updatable = false)
    private UUID taskId;

    @Column(name = "level", nullable = false)
    private int level;

    @Column(name = "sent_at", nullable = false, updatable = false)
    private Instant sentAt;

    /** Builds a log entry recording that {@code level} was sent for {@code taskId} at {@code sentAt}. */
    public static ReminderLog of(final UUID taskId, final ReminderLevel level, final Instant sentAt) {
        final ReminderLog log = new ReminderLog();
        log.taskId = taskId;
        log.level = level.level();
        log.sentAt = sentAt;
        return log;
    }
}
