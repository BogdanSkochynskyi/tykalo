package io.tykalo.telegram;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Holds the Telegram bot token, sourced from the {@code TELEGRAM_BOT_TOKEN} environment
 * variable via {@code telegram.bot.token}. Fails fast at startup when the token is missing,
 * so the application never runs without a usable bot credential.
 */
@Component
public class    TelegramBotProperties {

    private final String token;

    public TelegramBotProperties(@Value("${telegram.bot.token}") final String token) {
        if (token.isBlank()) {
            throw new IllegalStateException(
                    "TELEGRAM_BOT_TOKEN is not set. Create a bot via @BotFather (/newbot), then add the "
                    + "token to your .env file as TELEGRAM_BOT_TOKEN=... "
                    + "See README -> \"Telegram bot setup (@BotFather)\".");
        }
        this.token = token;
    }

    public String getToken() {
        return token;
    }
}