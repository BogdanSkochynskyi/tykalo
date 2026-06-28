package io.tykalo.menu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class AutoCloseSuppressionServiceTest {

    private static final UUID LIST_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final String KEY = "list:" + LIST_ID + ":autoclose-suppressed";

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> valueOps;

    @InjectMocks
    private AutoCloseSuppressionService service;

    @Test
    void suppress_writesTheFlagWithA1hTtl() {
        when(redis.opsForValue()).thenReturn(valueOps);

        service.suppress(LIST_ID);

        verify(valueOps).set(KEY, "1", Duration.ofHours(1));
    }

    @Test
    void isSuppressed_isTrue_whenTheKeyExists() {
        when(redis.hasKey(KEY)).thenReturn(true);

        assertThat(service.isSuppressed(LIST_ID)).isTrue();
    }

    @Test
    void isSuppressed_isFalse_whenTheKeyIsAbsent() {
        when(redis.hasKey(KEY)).thenReturn(false);

        assertThat(service.isSuppressed(LIST_ID)).isFalse();
    }

    @Test
    void isSuppressed_isFalse_whenRedisReturnsNull() {
        when(redis.hasKey(KEY)).thenReturn(null);

        assertThat(service.isSuppressed(LIST_ID)).isFalse();
    }
}
