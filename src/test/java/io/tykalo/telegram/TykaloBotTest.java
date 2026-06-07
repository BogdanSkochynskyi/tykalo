package io.tykalo.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
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
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;

@ExtendWith(MockitoExtension.class)
class TykaloBotTest {

    @Mock
    private TelegramCommandDispatcher dispatcher;

    @Mock
    private TelegramMessageGateway gateway;

    private TykaloBot bot;

    @BeforeEach
    void setUp() {
        bot = new TykaloBot(new TelegramBotProperties("test-token"), dispatcher, gateway);
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

    @Test
    void should_answerCallbackWithToast_when_handlerClaimsCallback() {
        // Arrange
        final Update update = callback("cb-1", 555L, 999L, "task:done:abc");
        when(dispatcher.dispatchCallback(update)).thenReturn(Optional.of("Done!"));

        // Act
        bot.handle(update);

        // Assert
        verify(gateway).answerCallback("cb-1", "Done!");
    }

    @Test
    void should_answerCallbackWithNoText_when_noHandlerClaimsCallback() {
        // Arrange — spinner must still be dismissed even when unclaimed
        final Update update = callback("cb-2", 555L, 999L, "noise");
        when(dispatcher.dispatchCallback(update)).thenReturn(Optional.empty());

        // Act
        bot.handle(update);

        // Assert
        verify(gateway).answerCallback(eq("cb-2"), isNull());
    }

    @Test
    void should_populateMdcFromCallback_during_callbackDispatch() {
        final Map<String, String> seen = new HashMap<>();
        when(dispatcher.dispatchCallback(any())).thenAnswer(invocation -> {
            seen.put("chatId", MDC.get("chatId"));
            seen.put("userId", MDC.get("userId"));
            return Optional.empty();
        });

        bot.handle(callback("cb-3", 555L, 999L, "task:done:abc"));

        assertThat(seen).containsEntry("chatId", "555").containsEntry("userId", "999");
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

    private static Update callback(final String id, final long chatId, final long userId, final String data) {
        final Message message = new Message();
        message.setMessageId(1);
        message.setChat(new Chat(chatId, "private"));

        final CallbackQuery query = new CallbackQuery();
        query.setId(id);
        query.setFrom(new User(userId, "Test", false));
        query.setMessage(message);
        query.setData(data);

        final Update update = new Update();
        update.setCallbackQuery(query);
        return update;
    }
}
