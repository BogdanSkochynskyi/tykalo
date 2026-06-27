package io.tykalo.list;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

/**
 * An item the user has "saved for later", mapped to the {@code pending_items} table (Flyway
 * {@code V20__pending_items_table.sql}). It is a bucket independent of the list lifecycle (TK-251/252):
 * a pending item outlives the list and task it came from. {@code originalListId} is a {@code SET NULL}
 * provenance link (deleting the source list only clears it), and {@code sourceTaskId} is a plain
 * reference with no FK because the source task is typically soft-deleted once deferred.
 *
 * <p>{@code originalListTags} is a snapshot of the source list's tags taken at defer time, used to match
 * the item against newly created lists with overlapping tags (TK-258). Like {@link Task} and
 * {@link ListMember}, the FK columns are held as raw UUIDs rather than {@code @ManyToOne} so the entity
 * stays proxy-free under {@code open-in-view: false}.
 */
@Entity
@Table(name = "pending_items")
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(of = "id")
public class PendingItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private @Nullable UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "original_list_id")
    private @Nullable UUID originalListId;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "original_list_tags", nullable = false, columnDefinition = "text[]")
    private List<String> originalListTags = new ArrayList<>();

    @Column(name = "source_task_id")
    private @Nullable UUID sourceTaskId;

    @CreationTimestamp
    @Column(name = "deferred_at", nullable = false, updatable = false)
    private @Nullable Instant deferredAt;

    @Column(name = "deferred_until")
    private @Nullable Instant deferredUntil;

    public Optional<UUID> getOriginalListId() {
        return Optional.ofNullable(originalListId);
    }

    public Optional<UUID> getSourceTaskId() {
        return Optional.ofNullable(sourceTaskId);
    }

    public Optional<Instant> getDeferredUntil() {
        return Optional.ofNullable(deferredUntil);
    }

    /**
     * Defers {@code title} for {@code userId}, capturing where it came from: {@code originalListId} and a
     * snapshot of that list's {@code originalListTags} (for later tag matching), plus the optional
     * {@code sourceTaskId} of the task it was deferred from. {@code deferredAt} is stamped on persist; any
     * {@code deferredUntil} reminder is set separately via the setter.
     */
    public static PendingItem defer(final UUID userId, final String title, final @Nullable UUID originalListId,
            final List<String> originalListTags, final @Nullable UUID sourceTaskId) {
        final PendingItem item = new PendingItem();
        item.userId = Objects.requireNonNull(userId, "userId");
        item.title = Objects.requireNonNull(title, "title");
        item.originalListId = originalListId;
        item.originalListTags = originalListTags == null ? new ArrayList<>() : new ArrayList<>(originalListTags);
        item.sourceTaskId = sourceTaskId;
        return item;
    }
}
