package io.tykalo.telegram.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

class OutboundMessageTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void of_thenJsonRoundTrip_preservesEveryFieldIncludingTheKeyboard() throws Exception {
        // Arrange
        final InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(
                        InlineKeyboardButton.builder().text("✅ 1").callbackData("task:done:x").build(),
                        InlineKeyboardButton.builder().text("↩️ 2").callbackData("task:undo:y").build()))
                .build();
        final OutboundMessage message = OutboundMessage.of(42L, "*Hi*", "MarkdownV2", keyboard);

        // Act — serialize and read back, exactly as the Redis queue does
        final OutboundMessage restored = mapper.readValue(mapper.writeValueAsString(message), OutboundMessage.class);

        // Assert
        assertThat(restored).isEqualTo(message);
        final InlineKeyboardMarkup rebuilt = restored.toKeyboard();
        assertThat(rebuilt).isNotNull();
        assertThat(rebuilt.getKeyboard()).hasSize(1);
        assertThat(rebuilt.getKeyboard().getFirst())
                .extracting(InlineKeyboardButton::getText, InlineKeyboardButton::getCallbackData)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("✅ 1", "task:done:x"),
                        org.assertj.core.groups.Tuple.tuple("↩️ 2", "task:undo:y"));
    }

    @Test
    void of_withoutKeyboard_yieldsNullKeyboard_andSurvivesRoundTrip() throws Exception {
        final OutboundMessage message = OutboundMessage.of(7L, "plain", null, null);

        final OutboundMessage restored = mapper.readValue(mapper.writeValueAsString(message), OutboundMessage.class);

        assertThat(restored).isEqualTo(message);
        assertThat(restored.keyboard()).isNull();
        assertThat(restored.toKeyboard()).isNull();
        assertThat(restored.parseMode()).isNull();
    }

    @Test
    void retried_advancesTheAttemptCounter_keepingIdentityAndContent() {
        final OutboundMessage first = OutboundMessage.of(42L, "text", null, null);

        final OutboundMessage second = first.retried();

        assertThat(second.attempt()).isEqualTo(1);
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.chatId()).isEqualTo(first.chatId());
        assertThat(second.text()).isEqualTo(first.text());
    }
}
