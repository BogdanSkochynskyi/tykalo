package io.tykalo.telegram;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;

/**
 * Wires the {@link TelegramMessageGateway}. When the bot is polling (production, the default), the
 * real API-backed gateway is used; otherwise — tests, tooling — a no-op fallback keeps dependent
 * beans constructible without opening a connection to Telegram. Mirrors the polling gate on
 * {@code TykaloBot}.
 */
@Configuration
public class TelegramMessagingConfig {

    @Bean
    @ConditionalOnProperty(name = "telegram.bot.polling.enabled", havingValue = "true", matchIfMissing = true)
    TelegramMessageGateway telegramMessageGateway(final TelegramBotProperties properties) {
        return new TelegramApiMessageGateway(new OkHttpTelegramClient(properties.getToken()));
    }

    @Bean
    @ConditionalOnMissingBean(TelegramMessageGateway.class)
    TelegramMessageGateway noOpTelegramMessageGateway() {
        return new NoOpTelegramMessageGateway();
    }
}
