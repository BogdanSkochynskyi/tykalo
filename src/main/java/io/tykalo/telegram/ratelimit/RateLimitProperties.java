package io.tykalo.telegram.ratelimit;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Tunables for the outbound rate-limit queue (TK-173), bound from {@code telegram.ratelimit.*}. The
 * defaults mirror Telegram's documented limits (30 messages/sec globally, 1/sec per chat) and the
 * ticket's retry policy (up to 5 attempts, exponential backoff from 1 second). Tests lower these to
 * exercise throttling and drop behavior without real delays.
 */
@Component
@ConfigurationProperties("telegram.ratelimit")
public class RateLimitProperties {

    private int globalPerSecond = 30;
    private int perChatPerSecond = 1;
    private int maxAttempts = 5;
    private Duration backoffBase = Duration.ofSeconds(1);

    public int getGlobalPerSecond() {
        return globalPerSecond;
    }

    public void setGlobalPerSecond(final int globalPerSecond) {
        this.globalPerSecond = globalPerSecond;
    }

    public int getPerChatPerSecond() {
        return perChatPerSecond;
    }

    public void setPerChatPerSecond(final int perChatPerSecond) {
        this.perChatPerSecond = perChatPerSecond;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(final int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public Duration getBackoffBase() {
        return backoffBase;
    }

    public void setBackoffBase(final Duration backoffBase) {
        this.backoffBase = backoffBase;
    }
}
