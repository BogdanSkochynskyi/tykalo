package io.tykalo.telegram.ratelimit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

/**
 * The single entry point for sending a new outgoing Telegram message (TK-173). Rather than calling
 * the Telegram API directly, producers enqueue here; {@link OutboundQueueWorker} drains the queue at
 * Telegram's allowed rate, retries on 429, and records anything it has to drop.
 *
 * <p>Messages are serialized to JSON and {@code LPUSH}ed onto a Redis list; the worker {@code RPOP}s
 * from the tail, giving FIFO order. {@code RPOP} is atomic, so even a multi-instance deploy never
 * delivers a queued message twice.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MessageQueueService {

    static final String QUEUE_KEY = "telegram:outbound:queue";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    /** Enqueues a message for paced delivery. The keyboard, when present, must be callback-only. */
    public void enqueue(final long chatId, final String text, final @Nullable String parseMode,
                        final @Nullable InlineKeyboardMarkup keyboard) {
        final OutboundMessage message = OutboundMessage.of(chatId, text, parseMode, keyboard);
        try {
            redis.opsForList().leftPush(QUEUE_KEY, objectMapper.writeValueAsString(message));
            log.debug("Enqueued outbound message {} to chat {}", message.id(), chatId);
        } catch (final JsonProcessingException e) {
            log.error("Failed to serialize outbound message to chat {}; dropping", chatId, e);
        }
    }
}
