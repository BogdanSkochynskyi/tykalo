package io.tykalo.user;

import io.tykalo.common.ZoneIdConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.jspecify.annotations.Nullable;

/**
 * A Telegram user of the bot. Mirrors the {@code users} table created by Flyway
 * migration {@code V1__users_table.sql}; time is stored in UTC.
 */
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(of = "id")
public class User {

    static final LocalTime DEFAULT_QUIET_HOURS_START = LocalTime.of(22, 0);
    static final LocalTime DEFAULT_QUIET_HOURS_END = LocalTime.of(7, 0);
    static final int DEFAULT_DIGEST_HOUR = 8;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private @Nullable UUID id;

    @Column(name = "tg_chat_id", nullable = false, unique = true)
    private Long tgChatId;

    @Column(name = "tg_username", length = 32)
    private @Nullable String tgUsername;

    @Convert(converter = ZoneIdConverter.class)
    @Column(name = "timezone", length = 64)
    private @Nullable ZoneId timezone;

    @Column(name = "quiet_hours_start")
    private @Nullable LocalTime quietHoursStart;

    @Column(name = "quiet_hours_end")
    private @Nullable LocalTime quietHoursEnd;

    @Column(name = "locale", length = 10)
    private @Nullable String locale;

    @Column(name = "digest_hour")
    private @Nullable Integer digestHour;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private @Nullable Instant createdAt;

    /**
     * Builds a new user with the default quiet hours (22:00–07:00) and an 08:00 morning digest.
     * The id and {@code createdAt} are populated by the persistence layer on insert.
     */
    public static User create(final Long tgChatId, final @Nullable String tgUsername,
                              final ZoneId timezone, final @Nullable String locale) {
        final User user = new User();
        user.tgChatId = tgChatId;
        user.tgUsername = tgUsername;
        user.timezone = timezone;
        user.locale = locale;
        user.quietHoursStart = DEFAULT_QUIET_HOURS_START;
        user.quietHoursEnd = DEFAULT_QUIET_HOURS_END;
        user.digestHour = DEFAULT_DIGEST_HOUR;
        return user;
    }
}
