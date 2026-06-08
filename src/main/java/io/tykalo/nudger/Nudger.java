package io.tykalo.nudger;

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
 * A trusted contact who applies graduated social pressure on an owner's overdue tasks, mapped to the
 * {@code nudgers} table (Flyway {@code V8__nudgers_tables.sql}). {@code ownerId} is the user who
 * invited the nudger; {@code nudgerUserId} is the user doing the nudging — both held as raw UUIDs
 * rather than {@code @ManyToOne}, matching {@link io.tykalo.list.Task}. The pairing walks
 * {@code PENDING → ACTIVE} on consent and {@code ACTIVE ⇄ PAUSED} thereafter.
 */
@Entity
@Table(name = "nudgers")
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(of = "id")
public class Nudger {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private @Nullable UUID id;

    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    @Column(name = "nudger_user_id", nullable = false, updatable = false)
    private UUID nudgerUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 8)
    private NudgerStatus status = NudgerStatus.PENDING;

    @Column(name = "karma_score", nullable = false)
    private int karmaScore;

    @CreationTimestamp
    @Column(name = "added_at", nullable = false, updatable = false)
    private @Nullable Instant addedAt;

    /**
     * Invites {@code nudgerUser} to be {@code owner}'s nudger: a {@link NudgerStatus#PENDING} pairing
     * with zero karma. Both users must already be persisted. Consent (TK-153) flips it to
     * {@code ACTIVE}.
     */
    public static Nudger invite(final User owner, final User nudgerUser) {
        final Nudger nudger = new Nudger();
        nudger.ownerId = Objects.requireNonNull(owner.getId(), "owner must be persisted");
        nudger.nudgerUserId = Objects.requireNonNull(nudgerUser.getId(), "nudger user must be persisted");
        nudger.status = NudgerStatus.PENDING;
        nudger.karmaScore = 0;
        return nudger;
    }
}
