package io.tykalo.telegram.ratelimit;

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
 * A message the outbound worker gave up on, mapped to {@code dropped_messages} (Flyway
 * {@code V14__dropped_messages_table.sql}). A row is written when a send exhausts its retry budget
 * after repeated 429s, or fails with a permanent error (e.g. a 400 or the user blocked the bot), so
 * the failure can be reviewed rather than silently lost.
 */
@Entity
@Table(name = "dropped_messages")
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(of = "id")
public class DroppedMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private @Nullable UUID id;

    @Column(name = "chat_id", nullable = false, updatable = false)
    private long chatId;

    @Column(name = "text", nullable = false, updatable = false)
    private String text;

    @Column(name = "attempts", nullable = false, updatable = false)
    private int attempts;

    @Column(name = "reason", updatable = false)
    private @Nullable String reason;

    @Column(name = "dropped_at", nullable = false, updatable = false)
    private Instant droppedAt;

    /** Builds a drop record for {@code message}, capturing why it could not be delivered. */
    public static DroppedMessage of(final OutboundMessage message, final @Nullable String reason,
                                    final Instant droppedAt) {
        final DroppedMessage dropped = new DroppedMessage();
        dropped.chatId = message.chatId();
        dropped.text = message.text();
        dropped.attempts = message.attempt();
        dropped.reason = reason;
        dropped.droppedAt = droppedAt;
        return dropped;
    }
}
