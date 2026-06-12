package io.tykalo.telegram;

import io.tykalo.telegram.ratelimit.MessageQueueService;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.longpolling.starter.SpringLongPollingBot;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.message.MaybeInaccessibleMessage;
import org.telegram.telegrambots.meta.api.objects.message.Message;

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
    private final TelegramMessageGateway gateway;
    private final MessageQueueService messageQueue;

    public TykaloBot(final TelegramBotProperties properties, final TelegramCommandDispatcher dispatcher,
                     final TelegramMessageGateway gateway, final MessageQueueService messageQueue) {
        this.botToken = properties.getToken();
        this.dispatcher = dispatcher;
        this.gateway = gateway;
        this.messageQueue = messageQueue;
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
        populateMdc(update);
        try {
            if (update.hasCallbackQuery()) {
                answerCallback(update);
            } else {
                dispatcher.dispatch(update).ifPresent(reply -> send(update, reply));
            }
        } catch (final RuntimeException e) {
            log.error("Failed to handle update {}", update.getUpdateId(), e);
        } finally {
            MDC.remove(MDC_USER_ID);
            MDC.remove(MDC_CHAT_ID);
        }
    }

    /** Routes the callback to its handler, then always answers it so Telegram's spinner stops. */
    private void answerCallback(final Update update) {
        final CallbackQuery query = update.getCallbackQuery();
        final String toast = dispatcher.dispatchCallback(update).orElse(null);
        gateway.answerCallback(query.getId(), toast);
    }

    private void populateMdc(final Update update) {
        if (update.hasCallbackQuery()) {
            final CallbackQuery query = update.getCallbackQuery();
            final MaybeInaccessibleMessage message = query.getMessage();
            putChatId(message == null ? null : message.getChatId());
            putUserId(query.getFrom());
            return;
        }
        final Message message = update.getMessage();
        if (message == null) {
            return;
        }
        putChatId(message.getChatId());
        putUserId(message.getFrom());
    }

    private void putChatId(final @Nullable Long chatId) {
        if (chatId != null) {
            MDC.put(MDC_CHAT_ID, String.valueOf(chatId));
        }
    }

    private void putUserId(final @Nullable User from) {
        if (from != null && from.getId() != null) {
            MDC.put(MDC_USER_ID, String.valueOf(from.getId()));
        }
    }

    /** Queues the plain-text reply for paced delivery through the rate-limit worker (TK-173). */
    private void send(final Update update, final String text) {
        messageQueue.enqueue(update.getMessage().getChatId(), text, null, null);
    }
}
