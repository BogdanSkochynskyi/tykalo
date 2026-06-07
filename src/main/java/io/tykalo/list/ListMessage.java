package io.tykalo.list;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
import org.jspecify.annotations.Nullable;

/**
 * The "live" Telegram message that mirrors a {@link TaskList} in a chat, mapped to the
 * {@code list_messages} table (Flyway {@code V4__list_messages_table.sql}). One row per
 * (list, chat): it remembers the {@code tgMessageId} so the bot can edit that single message in
 * place instead of posting a new one every time the list changes.
 *
 * <p>{@code listId} is held as a raw UUID rather than a {@code @ManyToOne} so the entity stays
 * proxy-free under {@code open-in-view: false}, mirroring {@link Task} and {@link TaskList}.
 */
@Entity
@Table(name = "list_messages")
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(of = "id")
public class ListMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private @Nullable UUID id;

    @Column(name = "list_id", nullable = false, updatable = false)
    private UUID listId;

    @Column(name = "tg_chat_id", nullable = false, updatable = false)
    private long tgChatId;

    @Column(name = "tg_message_id", nullable = false)
    private long tgMessageId;

    @Column(name = "last_rendered_at")
    private @Nullable Instant lastRenderedAt;

    /** Records that {@code tgMessageId} in {@code tgChatId} now mirrors the given list. */
    public static ListMessage of(final UUID listId, final long tgChatId, final long tgMessageId) {
        final ListMessage message = new ListMessage();
        message.listId = Objects.requireNonNull(listId, "listId");
        message.tgChatId = tgChatId;
        message.tgMessageId = tgMessageId;
        message.lastRenderedAt = Instant.now();
        return message;
    }

    /** Points this record at a freshly-sent message id and bumps the render timestamp. */
    public void refreshedTo(final long newMessageId) {
        this.tgMessageId = newMessageId;
        this.lastRenderedAt = Instant.now();
    }

    /** Bumps the render timestamp after an in-place edit (the message id is unchanged). */
    public void markRendered() {
        this.lastRenderedAt = Instant.now();
    }
}
