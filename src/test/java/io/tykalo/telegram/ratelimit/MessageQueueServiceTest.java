package io.tykalo.telegram.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

@ExtendWith(MockitoExtension.class)
class MessageQueueServiceTest {

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ListOperations<String, String> listOps;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void enqueue_serializesTheMessage_andLeftPushesItOntoTheQueue() throws Exception {
        // Arrange
        when(redis.opsForList()).thenReturn(listOps);
        final MessageQueueService service = new MessageQueueService(redis, mapper);
        final InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(
                        InlineKeyboardButton.builder().text("✅").callbackData("task:done:x").build()))
                .build();

        // Act
        service.enqueue(99L, "*Buy milk*", "MarkdownV2", keyboard);

        // Assert — pushed onto the shared queue key as a faithful JSON payload
        final ArgumentCaptor<String> json = ArgumentCaptor.captor();
        verify(listOps).leftPush(org.mockito.ArgumentMatchers.eq("telegram:outbound:queue"), json.capture());
        final OutboundMessage queued = mapper.readValue(json.getValue(), OutboundMessage.class);
        assertThat(queued.chatId()).isEqualTo(99L);
        assertThat(queued.text()).isEqualTo("*Buy milk*");
        assertThat(queued.parseMode()).isEqualTo("MarkdownV2");
        assertThat(queued.attempt()).isZero();
        assertThat(queued.id()).isNotBlank();
        assertThat(queued.toKeyboard()).isNotNull();
    }
}
