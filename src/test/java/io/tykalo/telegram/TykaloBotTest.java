package io.tykalo.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;

@ExtendWith(MockitoExtension.class)
class TykaloBotTest {

    @Mock
    private TelegramCommandDispatcher dispatcher;

    private TykaloBot bot;

    @BeforeEach
    void setUp() {
        bot = new TykaloBot(new TelegramBotProperties("test-token"), dispatcher);
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void should_populateMdcWithChatIdAndUserId_during_dispatch() {
        // Arrange — capture MDC state at the moment the dispatcher runs
        final Map<String, String> seenDuringDispatch = new HashMap<>();
        when(dispatcher.dispatch(any())).thenAnswer(invocation -> {
            seenDuringDispatch.put("chatId", MDC.get("chatId"));
            seenDuringDispatch.put("userId", MDC.get("userId"));
            return Optional.empty();
        });

        // Act
        bot.handle(update(555L, 999L, "/start"));

        // Assert — MDC carried both fields while dispatching, then was cleared
        assertThat(seenDuringDispatch).containsEntry("chatId", "555").containsEntry("userId", "999");
        assertThat(MDC.get("chatId")).isNull();
        assertThat(MDC.get("userId")).isNull();
    }

    @Test
    void should_notSetMdc_andNotFail_when_updateHasNoMessage() {
        // Arrange
        when(dispatcher.dispatch(any())).thenReturn(Optional.empty());
        final Update update = new Update();
        update.setUpdateId(7);

        // Act
        bot.handle(update);

        // Assert
        assertThat(MDC.get("chatId")).isNull();
        assertThat(MDC.get("userId")).isNull();
    }

    private static Update update(final long chatId, final long userId, final String text) {
        final User from = new User(userId, "Test", false);

        final Message message = new Message();
        message.setMessageId(1);
        message.setText(text);
        message.setFrom(from);
        message.setChat(new Chat(chatId, "private"));

        final Update update = new Update();
        update.setMessage(message);
        return update;
    }
}
