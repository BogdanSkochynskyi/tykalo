package io.tykalo.telegram;

import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.longpolling.starter.SpringLongPollingBot;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

/**
 * The long-polling Telegram bot. Delegates every update to {@link TelegramCommandDispatcher}
 * and sends any reply back through the Telegram client.
 *
 * <p>Only registered when {@code telegram.bot.polling.enabled} is true (the default), so tests
 * and other non-polling contexts never open a connection to Telegram.
 */
@Component
@ConditionalOnProperty(name = "telegram.bot.polling.enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
public class TykaloBot implements SpringLongPollingBot {

    private static final String MDC_USER_ID = "userId";
    private static final String MDC_CHAT_ID = "chatId";

    private final String botToken;
    private final TelegramCommandDispatcher dispatcher;
    private final TelegramClient telegramClient;

    public TykaloBot(final TelegramBotProperties properties, final TelegramCommandDispatcher dispatcher) {
        this.botToken = properties.getToken();
        this.dispatcher = dispatcher;
        this.telegramClient = new OkHttpTelegramClient(properties.getToken());
    }

    @Override
    public String getBotToken() {
        return botToken;
    }

    @Override
    public LongPollingUpdateConsumer getUpdatesConsumer() {
        return new LongPollingSingleThreadUpdateConsumer() {
            @Override
            public void consume(final Update update) {
                handle(update);
            }
        };
    }

    void handle(final Update update) {
        populateMdc(update.getMessage());
        try {
            dispatcher.dispatch(update).ifPresent(reply -> send(update, reply));
        } catch (final RuntimeException e) {
            log.error("Failed to handle update {}", update.getUpdateId(), e);
        } finally {
            MDC.remove(MDC_USER_ID);
            MDC.remove(MDC_CHAT_ID);
        }
    }

    private void populateMdc(final @Nullable Message message) {
        if (message == null) {
            return;
        }
        if (message.getChatId() != null) {
            MDC.put(MDC_CHAT_ID, String.valueOf(message.getChatId()));
        }
        if (message.getFrom() != null && message.getFrom().getId() != null) {
            MDC.put(MDC_USER_ID, String.valueOf(message.getFrom().getId()));
        }
    }

    // NOTE: outgoing messages will move onto the Redis-backed rate-limit queue in TK-173.
    private void send(final Update update, final String text) {
        final SendMessage message = SendMessage.builder()
                .chatId(update.getMessage().getChatId())
                .text(text)
                .build();
        try {
            telegramClient.execute(message);
        } catch (final TelegramApiException e) {
            log.error("Failed to send reply to chat {}", update.getMessage().getChatId(), e);
        }
    }
}
