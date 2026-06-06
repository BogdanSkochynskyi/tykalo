package io.tykalo.list;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

/**
 * A single actionable item inside a {@link TaskList}, mapped to the {@code tasks} table
 * (Flyway {@code V3__tasks_table.sql}); time is stored in UTC.
 *
 * <p>Only {@code title} is required — a title-only task is a checkbox. Adding {@code dueAt},
 * a {@code recurrenceRule} and Nudgers grows it into a full task with escalation. The
 * domain-optional fields expose {@link Optional} getters; {@code listId} and {@code ownerId}
 * are held as raw UUIDs rather than {@code @ManyToOne} so the entity stays proxy-free under
 * {@code open-in-view: false}, mirroring {@link TaskList}.
 */
@Entity
@Table(name = "tasks")
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(of = "id")
public class Task {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private @Nullable UUID id;

    @Column(name = "list_id", nullable = false)
    private UUID listId;

    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "description")
    private @Nullable String description;

    @Column(name = "due_at")
    private @Nullable Instant dueAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", length = 8)
    private @Nullable Priority priority;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 12)
    private TaskStatus status = TaskStatus.TODO;

    @Column(name = "recurrence_rule")
    private @Nullable String recurrenceRule;

    @Column(name = "gcal_event_id", length = 255)
    private @Nullable String gcalEventId;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "tags", nullable = false, columnDefinition = "text[]")
    private List<String> tags = new ArrayList<>();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private @Nullable Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private @Nullable Instant updatedAt;

    @Column(name = "archived_at")
    private @Nullable Instant archivedAt;

    public Optional<String> getDescription() {
        return Optional.ofNullable(description);
    }

    public Optional<Instant> getDueAt() {
        return Optional.ofNullable(dueAt);
    }

    public Optional<Priority> getPriority() {
        return Optional.ofNullable(priority);
    }

    public Optional<String> getRecurrenceRule() {
        return Optional.ofNullable(recurrenceRule);
    }

    public Optional<String> getGcalEventId() {
        return Optional.ofNullable(gcalEventId);
    }

    /**
     * Creates a title-only task (a checkbox) in the given list. The owner is inherited from the
     * list; status defaults to {@link TaskStatus#TODO}. The list must already be persisted.
     */
    public static Task create(final TaskList list, final String title) {
        final Task task = new Task();
        task.listId = Objects.requireNonNull(list.getId(), "list must be persisted before creating a task");
        task.ownerId = list.getOwnerId();
        task.title = title;
        task.status = TaskStatus.TODO;
        return task;
    }
}
