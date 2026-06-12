package io.tykalo.telegram.ratelimit;

import static io.tykalo.telegram.ratelimit.MessageQueueService.QUEUE_KEY;
import static io.tykalo.telegram.ratelimit.OutboundQueueWorker.RETRY_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tykalo.AbstractIntegrationTest;
import io.tykalo.telegram.ratelimit.OutboundQueueWorker.DispatchOutcome;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.ApiResponse;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

/**
 * Exercises {@link OutboundQueueWorker} against real Redis and Postgres (the drop table) with a
 * mocked Telegram client. The worker's loop is never started; tests drive {@code dispatchNext} and
 * {@code promoteDueRetries} directly for determinism.
 */
class OutboundQueueWorkerIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DroppedMessageRepository droppedRepository;

    private TelegramClient telegramClient;
    private RateLimitProperties props;
    private OutboundQueueWorker worker;
    private MessageQueueService queue;

    @BeforeEach
    void setUp() {
        final Set<String> keys = redis.keys("telegram:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
        droppedRepository.deleteAll();

        telegramClient = mock(TelegramClient.class);
        props = new RateLimitProperties();
        worker = new OutboundQueueWorker(redis, objectMapper, telegramClient, droppedRepository, props);
        queue = new MessageQueueService(redis, objectMapper);
    }

    @Test
    void dispatchNext_sendsAQueuedMessage_andReportsSent() throws Exception {
        // Arrange
        queue.enqueue(7001L, "*Hi*", "MarkdownV2", null);

        // Act
        final DispatchOutcome outcome = worker.dispatchNext(Instant.now());

        // Assert
        assertThat(outcome).isEqualTo(DispatchOutcome.SENT);
        final ArgumentCaptor<SendMessage> sent = ArgumentCaptor.captor();
        verify(telegramClient).execute(sent.capture());
        assertThat(sent.getValue().getChatId()).isEqualTo("7001");
        assertThat(sent.getValue().getText()).isEqualTo("*Hi*");
        assertThat(sent.getValue().getParseMode()).isEqualTo("MarkdownV2");
        assertThat(redis.opsForList().size(QUEUE_KEY)).isZero();
    }

    @Test
    void dispatchNext_defersTheSecondMessageToTheSameChat_withinOneSecond() throws Exception {
        // Arrange — two messages for the same chat
        queue.enqueue(7002L, "first", null, null);
        queue.enqueue(7002L, "second", null, null);
        final Instant now = Instant.now();

        // Act
        final DispatchOutcome first = worker.dispatchNext(now);
        final DispatchOutcome second = worker.dispatchNext(now);

        // Assert — only one send; the other is parked for retry, never delivered
        assertThat(first).isEqualTo(DispatchOutcome.SENT);
        assertThat(second).isEqualTo(DispatchOutcome.DEFERRED);
        verify(telegramClient, times(1)).execute(any(SendMessage.class));
        assertThat(redis.opsForZSet().size(RETRY_KEY)).isEqualTo(1);
    }

    @Test
    void dispatchNext_stopsSendingOnceTheGlobalPerSecondCapIsReached() throws Exception {
        // Arrange — cap of 2/sec, three messages to distinct chats so the per-chat limit never bites
        props.setGlobalPerSecond(2);
        queue.enqueue(7011L, "a", null, null);
        queue.enqueue(7012L, "b", null, null);
        queue.enqueue(7013L, "c", null, null);
        final Instant now = Instant.now();

        // Act
        final DispatchOutcome one = worker.dispatchNext(now);
        final DispatchOutcome two = worker.dispatchNext(now);
        final DispatchOutcome three = worker.dispatchNext(now);

        // Assert — third is held back and re-queued
        assertThat(one).isEqualTo(DispatchOutcome.SENT);
        assertThat(two).isEqualTo(DispatchOutcome.SENT);
        assertThat(three).isEqualTo(DispatchOutcome.GLOBAL_LIMITED);
        verify(telegramClient, times(2)).execute(any(SendMessage.class));
        assertThat(redis.opsForList().size(QUEUE_KEY)).isEqualTo(1);
    }

    @Test
    void dispatchNext_schedulesAnIncrementedRetry_onHttp429() throws Exception {
        // Arrange
        when(telegramClient.execute(any(SendMessage.class))).thenThrow(tooManyRequests());
        queue.enqueue(7021L, "text", null, null);

        // Act
        final DispatchOutcome outcome = worker.dispatchNext(Instant.now());

        // Assert — parked for retry with attempt advanced, nothing dropped yet
        assertThat(outcome).isEqualTo(DispatchOutcome.DEFERRED);
        assertThat(droppedRepository.count()).isZero();
        final Set<String> retries = redis.opsForZSet().range(RETRY_KEY, 0, -1);
        assertThat(retries).hasSize(1);
        final OutboundMessage retried = objectMapper
                .readValue(retries.iterator().next(), OutboundMessage.class);
        assertThat(retried.attempt()).isEqualTo(1);
    }

    @Test
    void dispatchNext_dropsToTheTable_whenRetriesAreExhausted() throws Exception {
        // Arrange — a message already at the attempt ceiling; one more 429 must end it
        props.setMaxAttempts(5);
        when(telegramClient.execute(any(SendMessage.class))).thenThrow(tooManyRequests());
        final OutboundMessage exhausted = new OutboundMessage("id-1", 7031L, "text", null, null, 5);
        redis.opsForList().leftPush(QUEUE_KEY, objectMapper.writeValueAsString(exhausted));

        // Act
        final DispatchOutcome outcome = worker.dispatchNext(Instant.now());

        // Assert — recorded for review, not re-queued
        assertThat(outcome).isEqualTo(DispatchOutcome.DROPPED);
        assertThat(redis.opsForZSet().size(RETRY_KEY)).isZero();
        final var dropped = droppedRepository.findAll();
        assertThat(dropped).hasSize(1);
        assertThat(dropped.getFirst().getChatId()).isEqualTo(7031L);
        assertThat(dropped.getFirst().getAttempts()).isEqualTo(5);
        assertThat(dropped.getFirst().getReason()).contains("429");
    }

    @Test
    void dispatchNext_dropsImmediately_onAPermanentTelegramError() throws Exception {
        // Arrange — a non-429 failure is not worth retrying
        when(telegramClient.execute(any(SendMessage.class))).thenThrow(new TelegramApiException("bad request"));
        queue.enqueue(7041L, "text", null, null);

        // Act
        final DispatchOutcome outcome = worker.dispatchNext(Instant.now());

        // Assert
        assertThat(outcome).isEqualTo(DispatchOutcome.DROPPED);
        assertThat(redis.opsForZSet().size(RETRY_KEY)).isZero();
        assertThat(droppedRepository.count()).isEqualTo(1);
    }

    @Test
    void promoteDueRetries_movesElapsedRetriesBackOntoTheQueue() throws Exception {
        // Arrange — a retry whose backoff window has already passed
        props.setBackoffBase(Duration.ZERO);
        when(telegramClient.execute(any(SendMessage.class))).thenThrow(tooManyRequests());
        queue.enqueue(7051L, "text", null, null);
        worker.dispatchNext(Instant.now());
        assertThat(redis.opsForZSet().size(RETRY_KEY)).isEqualTo(1);

        // Act — promote with a clock comfortably past the (zero) backoff
        worker.promoteDueRetries(Instant.now().plusSeconds(1));

        // Assert — back on the main queue, off the retry set
        assertThat(redis.opsForZSet().size(RETRY_KEY)).isZero();
        assertThat(redis.opsForList().size(QUEUE_KEY)).isEqualTo(1);
    }

    @Test
    void dispatchNext_returnsEmpty_whenNothingIsQueued() {
        assertThat(worker.dispatchNext(Instant.now())).isEqualTo(DispatchOutcome.EMPTY);
        verifyNoInteractions(telegramClient);
    }

    private TelegramApiRequestException tooManyRequests() {
        final ApiResponse<?> response = ApiResponse.builder()
                .ok(false)
                .errorCode(429)
                .errorDescription("Too Many Requests")
                .build();
        return new TelegramApiRequestException("Too Many Requests", response);
    }
}
