package io.tykalo.menu;

import java.time.Duration;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Remembers, per list, that a user just chose "Keep open" in answer to the auto-close prompt (TK-253), so
 * the detector does not re-ask for a while. Backed by Redis under {@code list:{listId}:autoclose-suppressed}
 * with a 1h TTL, mirroring {@link io.tykalo.telegram.conversation.ConversationStateService}'s use of
 * {@link StringRedisTemplate}: the flag expires on its own, so "keep open" naturally lapses after an hour
 * rather than suppressing the prompt forever.
 *
 * <p>Suppression is per-list (not per-user): once any editor says "keep open", the whole list stops
 * prompting for the window, which avoids nagging a shared list's other editors.
 */
@Service
@RequiredArgsConstructor
public class AutoCloseSuppressionService {

    static final Duration TTL = Duration.ofHours(1);

    private final StringRedisTemplate redis;

    /** Suppresses the auto-close prompt for {@code listId} for the next hour. */
    public void suppress(final UUID listId) {
        redis.opsForValue().set(key(listId), "1", TTL);
    }

    /** Whether the auto-close prompt is currently suppressed for {@code listId}. */
    public boolean isSuppressed(final UUID listId) {
        return Boolean.TRUE.equals(redis.hasKey(key(listId)));
    }

    private String key(final UUID listId) {
        return "list:" + listId + ":autoclose-suppressed";
    }
}
