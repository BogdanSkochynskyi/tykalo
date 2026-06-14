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
import java.util.Objects;
import java.util.UUID;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.jspecify.annotations.Nullable;

/**
 * Grants one user a {@link ListMemberRole} on one shared {@link TaskList}, mapped to the
 * {@code list_members} table (Flyway {@code V15__list_members_table.sql}). The foundation for
 * Phase 1.5b sharing: UNIQUE(list_id, user_id) keeps one role per user per list.
 *
 * <p>{@code listId} and {@code userId} are held as raw UUIDs rather than {@code @ManyToOne} so the
 * entity stays proxy-free under {@code open-in-view: false}, matching {@link TaskList},
 * {@link ListMessage} and {@link io.tykalo.nudger.Nudger}. {@code role} and {@code status} are mutable
 * (ownership transfer / role changes in TK-194; PENDING → ACTIVE on accept in TK-193); identity columns
 * are not.
 *
 * <p>{@code status} (TK-193) gates access: a membership is created {@link ListMemberStatus#PENDING}
 * when invited and only grants its role once {@link ListMemberStatus#ACTIVE}. {@code invitedBy} records
 * who sent the invite so accept/decline can notify them; it is {@code null} for direct/backfilled rows.
 */
@Entity
@Table(name = "list_members")
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(of = "id")
public class ListMember {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private @Nullable UUID id;

    @Column(name = "list_id", nullable = false, updatable = false)
    private UUID listId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 8)
    private ListMemberRole role;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 8)
    private ListMemberStatus status = ListMemberStatus.ACTIVE;

    @Column(name = "invited_by")
    private @Nullable UUID invitedBy;

    @CreationTimestamp
    @Column(name = "joined_at", nullable = false, updatable = false)
    private @Nullable Instant joinedAt;

    /** Grants {@code userId} the given {@code role} on {@code listId} as an {@link ListMemberStatus#ACTIVE} member. */
    public static ListMember of(final UUID listId, final UUID userId, final ListMemberRole role) {
        final ListMember member = new ListMember();
        member.listId = Objects.requireNonNull(listId, "listId");
        member.userId = Objects.requireNonNull(userId, "userId");
        member.role = Objects.requireNonNull(role, "role");
        member.status = ListMemberStatus.ACTIVE;
        return member;
    }

    /** Grants {@code userId} the {@link ListMemberRole#OWNER} role on {@code listId} (active). */
    public static ListMember owner(final UUID listId, final UUID userId) {
        return of(listId, userId, ListMemberRole.OWNER);
    }

    /**
     * A {@link ListMemberStatus#PENDING} invite of {@code userId} to {@code listId} with the given
     * {@code role}, sent by {@code invitedBy}. Grants no access until accepted (TK-193).
     */
    public static ListMember pendingInvite(final UUID listId, final UUID userId, final ListMemberRole role,
            final UUID invitedBy) {
        final ListMember member = of(listId, userId, role);
        member.status = ListMemberStatus.PENDING;
        member.invitedBy = Objects.requireNonNull(invitedBy, "invitedBy");
        return member;
    }
}
