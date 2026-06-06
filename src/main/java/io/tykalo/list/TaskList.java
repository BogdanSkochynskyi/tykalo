package io.tykalo.list;

import io.tykalo.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.jspecify.annotations.Nullable;

/**
 * A container for tasks. Named {@code TaskList} to avoid clashing with {@link java.util.List};
 * the underlying table is {@code lists} (Flyway {@code V2__lists_table.sql}). The owner is held
 * as a raw UUID rather than a {@code @ManyToOne} so it stays proxy-free under
 * {@code open-in-view: false}.
 */
@Entity
@Table(name = "lists")
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(of = "id")
public class TaskList {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private @Nullable UUID id;

    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 16)
    private ListType type;

    @Column(name = "recurrence_rule")
    private @Nullable String recurrenceRule;

    @Enumerated(EnumType.STRING)
    @Column(name = "nudger_default_policy", nullable = false, length = 16)
    private NudgerDefaultPolicy nudgerDefaultPolicy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private @Nullable Instant createdAt;

    @Column(name = "archived_at")
    private @Nullable Instant archivedAt;

    /** Creates a CHECKLIST (Nudgers off). */
    public static TaskList checklist(final User owner, final String name) {
        return create(owner, name, ListType.CHECKLIST);
    }

    /** Creates a ROUTINE (Nudgers opt-in). */
    public static TaskList routine(final User owner, final String name) {
        return create(owner, name, ListType.ROUTINE);
    }

    /** Creates a PROJECT (Nudgers decided per task). */
    public static TaskList project(final User owner, final String name) {
        return create(owner, name, ListType.PROJECT);
    }

    /** Creates the per-user Inbox (name "Inbox", Nudgers off). */
    public static TaskList inbox(final User owner) {
        return create(owner, "Inbox", ListType.INBOX);
    }

    private static TaskList create(final User owner, final String name, final ListType type) {
        final TaskList list = new TaskList();
        list.ownerId = Objects.requireNonNull(owner.getId(), "owner must be persisted before creating a list");
        list.name = name;
        list.type = type;
        list.nudgerDefaultPolicy = type.getDefaultNudgerPolicy();
        return list;
    }
}
