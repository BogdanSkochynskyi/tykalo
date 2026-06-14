package io.tykalo.telegram.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import io.tykalo.AbstractIntegrationTest;
import io.tykalo.list.ListType;
import io.tykalo.telegram.conversation.ConversationState.AddingItems;
import io.tykalo.telegram.conversation.ConversationState.CreatingListName;
import io.tykalo.telegram.conversation.ConversationState.Idle;
import io.tykalo.telegram.conversation.ConversationState.MainMenu;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Exercises {@link ConversationStateService} against real Redis: round-trips through JSON, the 24h
 * TTL, and the clear-on-Idle / expiry semantics.
 */
class ConversationStatePersistenceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ConversationStateService service;

    @Autowired
    private StringRedisTemplate redis;

    private final UUID userId = UUID.randomUUID();

    private String key() {
        return "user:" + userId + ":state";
    }

    @Test
    void setThenGet_roundTripsThroughRedis() {
        final UUID listId = UUID.randomUUID();
        service.setState(userId, new AddingItems(listId, 13));

        assertThat(service.getState(userId)).isEqualTo(new AddingItems(listId, 13));
    }

    @Test
    void setState_appliesA24hTtl() {
        service.setState(userId, new CreatingListName(ListType.PROJECT, 5));

        final Long ttlSeconds = redis.getExpire(key(), TimeUnit.SECONDS);
        assertThat(ttlSeconds).isNotNull();
        // Allow a little slack below the 24h ceiling for execution time.
        assertThat(ttlSeconds).isBetween(Duration.ofHours(23).toSeconds(), Duration.ofHours(24).toSeconds());
    }

    @Test
    void getState_returnsIdle_whenKeyHasExpiredOrIsAbsent() {
        assertThat(service.getState(userId)).isEqualTo(new Idle());
    }

    @Test
    void setState_idle_removesTheKey() {
        service.setState(userId, new MainMenu());
        assertThat(redis.hasKey(key())).isTrue();

        service.setState(userId, new Idle());

        assertThat(redis.hasKey(key())).isFalse();
        assertThat(service.getState(userId)).isEqualTo(new Idle());
    }

    @Test
    void clearState_removesTheKey() {
        service.setState(userId, new MainMenu());

        service.clearState(userId);

        assertThat(redis.hasKey(key())).isFalse();
    }
}
