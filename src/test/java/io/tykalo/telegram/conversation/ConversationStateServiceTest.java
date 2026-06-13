package io.tykalo.telegram.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tykalo.telegram.conversation.ConversationState.AddingItems;
import io.tykalo.telegram.conversation.ConversationState.Idle;
import io.tykalo.telegram.conversation.ConversationState.ListView;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class ConversationStateServiceTest {

    private static final UUID USER_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final String KEY = "user:" + USER_ID + ":state";

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> valueOps;

    private ConversationStateService service;

    @BeforeEach
    void setUp() {
        service = new ConversationStateService(redis, new ObjectMapper());
    }

    @Test
    void getState_returnsIdle_whenNothingStored() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(KEY)).thenReturn(null);

        assertThat(service.getState(USER_ID)).isEqualTo(new Idle());
    }

    @Test
    void getState_deserializesTheStoredState() {
        final UUID listId = UUID.randomUUID();
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(KEY)).thenReturn("{\"@type\":\"LIST_VIEW\",\"listId\":\"" + listId + "\"}");

        assertThat(service.getState(USER_ID)).isEqualTo(new ListView(listId));
    }

    @Test
    void getState_resetsToIdle_andClears_whenStoredJsonIsUnreadable() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(KEY)).thenReturn("{not valid json");

        assertThat(service.getState(USER_ID)).isEqualTo(new Idle());
        verify(redis).delete(KEY);
    }

    @Test
    void setState_writesJsonWithA24hTtl() {
        when(redis.opsForValue()).thenReturn(valueOps);
        final UUID listId = UUID.randomUUID();

        service.setState(USER_ID, new AddingItems(listId));

        final ArgumentCaptor<String> json = ArgumentCaptor.captor();
        verify(valueOps).set(eq(KEY), json.capture(), eq(Duration.ofHours(24)));
        assertThat(json.getValue()).contains("\"@type\":\"ADDING_ITEMS\"").contains(listId.toString());
    }

    @Test
    void setState_idle_clearsTheKey_withoutWriting() {
        service.setState(USER_ID, new Idle());

        verify(redis).delete(KEY);
        verify(redis, never()).opsForValue();
    }

    @Test
    void clearState_deletesTheKey() {
        service.clearState(USER_ID);

        verify(redis).delete(KEY);
    }
}
