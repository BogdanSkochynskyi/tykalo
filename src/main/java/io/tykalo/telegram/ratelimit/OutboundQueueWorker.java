package io.tykalo.telegram.ratelimit;

import static io.tykalo.telegram.ratelimit.MessageQueueService.QUEUE_KEY;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.ResponseParameters;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

/**
 * Drains the Redis-backed outbound queue (TK-173) and delivers messages within Telegram's rate
 * limits: at most {@code globalPerSecond} sends across all chats and {@code perChatPerSecond} per
 * individual chat. A 429 schedules an exponential-backoff retry; once a message exhausts its retry
 * budget — or fails permanently — it is recorded in {@code dropped_messages} for review.
 *
 * <p>Runs on a single dedicated virtual thread (started on application-ready, only when the bot is
 * actually polling) rather than on the shared {@code @Scheduled} pool, so a slow network send can
 * never delay the cron sweeps. No distributed lock is needed: {@code RPOP} hands each queued message
 * to exactly one drainer, and the per-second counters live in shared Redis, so a multi-instance
 * deploy stays within the global limit on its own.
 *
 * <p>Pacing keys in Redis: a per-second global counter ({@code telegram:ratelimit:global:{sec}}), a
 * short-lived per-chat key ({@code telegram:ratelimit:chat:{chatId}}), and a retry sorted-set keyed
 * by eligibility time ({@code telegram:outbound:retry}). A message blocked by the per-chat limit is
 * parked in that same sorted-set until the chat frees up, so the worker never busy-spins on it.
 */
@Component
@ConditionalOnProperty(name = "telegram.bot.polling.enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
public class OutboundQueueWorker {

    static final String RETRY_KEY = "telegram:outbound:retry";
    private static final String GLOBAL_PREFIX = "telegram:ratelimit:global:";
    private static final String CHAT_PREFIX = "telegram:ratelimit:chat:";
    private static final Duration GLOBAL_COUNTER_TTL = Duration.ofSeconds(2);
    private static final long IDLE_SLEEP_MS = 100;
    private static final long ERROR_BACKOFF_MS = 1_000;

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final TelegramClient telegramClient;
    private final DroppedMessageRepository droppedRepository;
    private final RateLimitProperties props;

    private volatile boolean running;
    private @Nullable Thread worker;

    public OutboundQueueWorker(final StringRedisTemplate redis, final ObjectMapper objectMapper,
                               final TelegramClient telegramClient,
                               final DroppedMessageRepository droppedRepository,
                               final RateLimitProperties props) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.telegramClient = telegramClient;
        this.droppedRepository = droppedRepository;
        this.props = props;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        running = true;
        worker = Thread.ofVirtual().name("tg-outbound-worker").start(this::runLoop);
        log.info("Outbound message worker started");
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (worker != null) {
            worker.interrupt();
        }
    }

    private void runLoop() {
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                final Instant now = Instant.now();
                promoteDueRetries(now);
                switch (dispatchNext(now)) {
                    case EMPTY -> sleepQuietly(IDLE_SLEEP_MS);
                    case GLOBAL_LIMITED -> sleepQuietly(millisUntilNextSecond(now));
                    default -> { /* sent, deferred or dropped — keep draining */ }
                }
            } catch (final RuntimeException e) {
                log.error("Outbound worker iteration failed", e);
                sleepQuietly(ERROR_BACKOFF_MS);
            }
        }
        log.info("Outbound message worker stopped");
    }

    /** Moves every retry whose backoff has elapsed back onto the main queue. Package-private for tests. */
    void promoteDueRetries(final Instant now) {
        final Set<String> due = redis.opsForZSet().rangeByScore(RETRY_KEY, 0, now.toEpochMilli());
        if (due == null) {
            return;
        }
        for (final String member : due) {
            final Long removed = redis.opsForZSet().remove(RETRY_KEY, member);
            if (removed != null && removed > 0) {
                redis.opsForList().leftPush(QUEUE_KEY, member);
            }
        }
    }

    /** Pops at most one queued message and routes it; returns what happened. Package-private for tests. */
    DispatchOutcome dispatchNext(final Instant now) {
        final String json = redis.opsForList().rightPop(QUEUE_KEY);
        if (json == null) {
            return DispatchOutcome.EMPTY;
        }
        final OutboundMessage message = deserialize(json);
        if (message == null) {
            return DispatchOutcome.DROPPED;
        }
        return dispatch(message, now);
    }

    private DispatchOutcome dispatch(final OutboundMessage message, final Instant now) {
        final long chatTtl = chatTtlMillis(message.chatId());
        if (chatTtl > 0) {
            scheduleAt(message, now.plusMillis(chatTtl));
            return DispatchOutcome.DEFERRED;
        }
        if (!reserveGlobalSlot(now)) {
            pushBack(message);
            return DispatchOutcome.GLOBAL_LIMITED;
        }
        reserveChatSlot(message.chatId());
        return send(message, now);
    }

    private DispatchOutcome send(final OutboundMessage message, final Instant now) {
        try {
            telegramClient.execute(buildSendMessage(message));
            log.debug("Delivered outbound message {} to chat {}", message.id(), message.chatId());
            return DispatchOutcome.SENT;
        } catch (final TelegramApiRequestException e) {
            if (e.getErrorCode() == 429) {
                return handleTooManyRequests(message, retryAfterSeconds(e), now);
            }
            return drop(message, "Telegram error " + e.getErrorCode() + ": " + e.getMessage(), now);
        } catch (final TelegramApiException e) {
            return drop(message, "Telegram send failed: " + e.getMessage(), now);
        }
    }

    private DispatchOutcome handleTooManyRequests(final OutboundMessage message,
                                                  final @Nullable Integer retryAfterSeconds, final Instant now) {
        final int nextAttempt = message.attempt() + 1;
        if (nextAttempt > props.getMaxAttempts()) {
            return drop(message, "Exhausted retries after HTTP 429 (" + message.attempt() + " attempts)", now);
        }
        long backoffMs = props.getBackoffBase().toMillis() << message.attempt();
        if (retryAfterSeconds != null) {
            backoffMs = Math.max(backoffMs, retryAfterSeconds * 1_000L);
        }
        scheduleAt(message.retried(), now.plusMillis(backoffMs));
        log.warn("HTTP 429 for chat {}; retry {} scheduled in {} ms", message.chatId(), nextAttempt, backoffMs);
        return DispatchOutcome.DEFERRED;
    }

    private DispatchOutcome drop(final OutboundMessage message, final String reason, final Instant now) {
        droppedRepository.save(DroppedMessage.of(message, reason, now));
        log.error("Dropped outbound message {} to chat {}: {}", message.id(), message.chatId(), reason);
        return DispatchOutcome.DROPPED;
    }

    private boolean reserveGlobalSlot(final Instant now) {
        final String key = GLOBAL_PREFIX + now.getEpochSecond();
        final Long count = redis.opsForValue().increment(key);
        if (count == null) {
            return false;
        }
        if (count == 1L) {
            redis.expire(key, GLOBAL_COUNTER_TTL);
        }
        return count <= props.getGlobalPerSecond();
    }

    private void reserveChatSlot(final long chatId) {
        redis.opsForValue().set(chatKey(chatId), "1", chatWindow());
    }

    private long chatTtlMillis(final long chatId) {
        final Long ttl = redis.getExpire(chatKey(chatId), TimeUnit.MILLISECONDS);
        return ttl != null && ttl > 0 ? ttl : 0;
    }

    private void scheduleAt(final OutboundMessage message, final Instant when) {
        final String json = serialize(message);
        if (json != null) {
            redis.opsForZSet().add(RETRY_KEY, json, when.toEpochMilli());
        }
    }

    private void pushBack(final OutboundMessage message) {
        final String json = serialize(message);
        if (json != null) {
            redis.opsForList().rightPush(QUEUE_KEY, json);
        }
    }

    private SendMessage buildSendMessage(final OutboundMessage message) {
        return SendMessage.builder()
                .chatId(message.chatId())
                .text(message.text())
                .parseMode(message.parseMode())
                .replyMarkup(message.toKeyboard())
                .build();
    }

    private @Nullable Integer retryAfterSeconds(final TelegramApiRequestException e) {
        final ResponseParameters params = e.getParameters();
        return params == null ? null : params.getRetryAfter();
    }

    private Duration chatWindow() {
        final int perChat = Math.max(1, props.getPerChatPerSecond());
        return Duration.ofMillis(1_000L / perChat);
    }

    private static long millisUntilNextSecond(final Instant now) {
        return 1_000L - Math.floorMod(now.toEpochMilli(), 1_000L);
    }

    private @Nullable OutboundMessage deserialize(final String json) {
        try {
            return objectMapper.readValue(json, OutboundMessage.class);
        } catch (final JsonProcessingException e) {
            log.error("Discarding unparseable queued message: {}", json, e);
            return null;
        }
    }

    private @Nullable String serialize(final OutboundMessage message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (final JsonProcessingException e) {
            log.error("Failed to serialize outbound message {}; dropping", message.id(), e);
            return null;
        }
    }

    private void sleepQuietly(final long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String chatKey(final long chatId) {
        return CHAT_PREFIX + chatId;
    }

    /** The outcome of handling one queued message, used to pace the worker loop. */
    enum DispatchOutcome {
        SENT,
        DEFERRED,
        GLOBAL_LIMITED,
        DROPPED,
        EMPTY
    }
}
