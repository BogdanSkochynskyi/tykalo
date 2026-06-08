package io.tykalo.nudger;

import io.tykalo.list.Task;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.jspecify.annotations.Nullable;

/**
 * One link in the per-task Nudger assignment (TK-158), mapped to the {@code task_nudgers} table
 * (Flyway {@code V12__task_nudgers.sql}): the {@link Task} pins the {@link Nudger} escalation should
 * reach. Both ends are raw UUIDs rather than {@code @ManyToOne}, matching {@link Task} and
 * {@link Nudger}; the {@code (task_id, nudger_id)} uniqueness is enforced by the table constraint.
 *
 * <p>A task with no rows here (and {@code nudgers_private = false}) falls back to the owner's full
 * active Nudger set — the TK-156 default; rows present narrow escalation to exactly those Nudgers.
 */
@Entity
@Table(name = "task_nudgers")
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(of = "id")
public class TaskNudger {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private @Nullable UUID id;

    @Column(name = "task_id", nullable = false, updatable = false)
    private UUID taskId;

    @Column(name = "nudger_id", nullable = false, updatable = false)
    private UUID nudgerId;

    /** Links {@code task} to {@code nudger}; both must already be persisted. */
    public static TaskNudger of(final UUID taskId, final UUID nudgerId) {
        final TaskNudger link = new TaskNudger();
        link.taskId = Objects.requireNonNull(taskId, "taskId");
        link.nudgerId = Objects.requireNonNull(nudgerId, "nudgerId");
        return link;
    }
}
