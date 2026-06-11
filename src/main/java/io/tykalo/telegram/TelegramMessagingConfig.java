package io.tykalo.telegram;

import io.tykalo.telegram.ratelimit.MessageQueueService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.meta.generics.TelegramClient;

/**
 * Wires the {@link TelegramMessageGateway} and the shared {@link TelegramClient}. When the bot is
 * polling (production, the default), the real API-backed gateway and a live client are used;
 * otherwise — tests, tooling — a no-op gateway keeps dependent beans constructible without opening a
 * connection to Telegram. Mirrors the polling gate on {@code TykaloBot}. The same {@link TelegramClient}
 * is injected into the rate-limit worker so all actual sends share one client.
 */
@Configuration
public class TelegramMessagingConfig {

    @Bean
    @ConditionalOnProperty(name = "telegram.bot.polling.enabled", havingValue = "true", matchIfMissing = true)
    TelegramClient telegramClient(final TelegramBotProperties properties) {
        return new OkHttpTelegramClient(properties.getToken());
    }

    @Bean
    @ConditionalOnProperty(name = "telegram.bot.polling.enabled", havingValue = "true", matchIfMissing = true)
    TelegramMessageGateway telegramMessageGateway(final TelegramClient telegramClient,
                                                  final MessageQueueService messageQueue) {
        return new TelegramApiMessageGateway(telegramClient, messageQueue);
    }

    @Bean
    @ConditionalOnMissingBean(TelegramMessageGateway.class)
    TelegramMessageGateway noOpTelegramMessageGateway() {
        return new NoOpTelegramMessageGateway();
    }
}
