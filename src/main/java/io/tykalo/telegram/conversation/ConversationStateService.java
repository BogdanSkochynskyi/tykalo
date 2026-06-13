package io.tykalo.telegram.conversation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Stores each user's {@link ConversationState} in Redis under {@code user:{userId}:state}, JSON
 * serialized with a 24h TTL so an abandoned flow expires on its own rather than trapping the user.
 * The {@code userId} is the internal {@code User} UUID.
 *
 * <p>{@link #getState} never returns {@code null}: a missing key — or, defensively, an unreadable one
 * left by a schema change — resolves to {@link ConversationState.Idle}. {@link ConversationState.Idle}
 * is the absence of a flow, so storing it just clears the key, keeping Redis free of "empty" entries.
 *
 * <p>Uses the Jackson 2 {@link ObjectMapper} the rate-limit queue already supplies; Spring Boot 4
 * auto-configures Jackson 3 for the web layer, so this is the only {@code databind} mapper bean.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ConversationStateService {

    static final Duration TTL = Duration.ofHours(24);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    /** The user's current state, or {@link ConversationState.Idle} when none is stored. */
    public ConversationState getState(final UUID userId) {
        final String json = redis.opsForValue().get(key(userId));
        if (json == null) {
            return new ConversationState.Idle();
        }
        try {
            return objectMapper.readValue(json, ConversationState.class);
        } catch (final JsonProcessingException e) {
            log.warn("Unreadable conversation state for user {}; resetting to Idle", userId, e);
            clearState(userId);
            return new ConversationState.Idle();
        }
    }

    /** Persists {@code state} with a fresh 24h TTL. Storing {@link ConversationState.Idle} clears it. */
    public void setState(final UUID userId, final ConversationState state) {
        if (state instanceof ConversationState.Idle) {
            clearState(userId);
            return;
        }
        try {
            redis.opsForValue().set(key(userId), objectMapper.writeValueAsString(state), TTL);
            log.debug("Set conversation state of user {} to {}", userId, state);
        } catch (final JsonProcessingException e) {
            log.error("Failed to serialize conversation state {} for user {}", state, userId, e);
        }
    }

    /** Drops any stored state, returning the user to {@link ConversationState.Idle}. */
    public void clearState(final UUID userId) {
        redis.delete(key(userId));
    }

    private String key(final UUID userId) {
        return "user:" + userId + ":state";
    }
}
