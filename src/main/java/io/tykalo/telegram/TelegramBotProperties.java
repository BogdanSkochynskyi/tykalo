package io.tykalo.telegram;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Holds the Telegram bot token and public username, sourced from the {@code TELEGRAM_BOT_TOKEN}
 * and {@code TELEGRAM_BOT_USERNAME} environment variables via {@code telegram.bot.*}. Fails fast at
 * startup when the token is missing, so the application never runs without a usable bot credential.
 * The username (without the leading {@code @}) is used to build {@code t.me/<username>?start=...}
 * invite deep-links (TK-152).
 */
@Component
public class    TelegramBotProperties {

    private final String token;
    private final String username;

    public TelegramBotProperties(@Value("${telegram.bot.token}") final String token,
                                 @Value("${telegram.bot.username:}") final String username) {
        if (token.isBlank()) {
            throw new IllegalStateException(
                    "TELEGRAM_BOT_TOKEN is not set. Create a bot via @BotFather (/newbot), then add the "
                    + "token to your .env file as TELEGRAM_BOT_TOKEN=... "
                    + "See README -> \"Telegram bot setup (@BotFather)\".");
        }
        this.token = token;
        this.username = username.strip();
    }

    public String getToken() {
        return token;
    }

    public String getUsername() {
        return username;
    }
}