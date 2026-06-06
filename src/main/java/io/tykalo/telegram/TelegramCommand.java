package io.tykalo.telegram;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a bean method as the handler for a Telegram command (e.g. {@code "/start"}).
 * Handlers must have the signature {@code String handle(Update)}; the returned text
 * is sent back to the chat (return {@code null} to stay silent).
 *
 * <p>Discovered and routed by {@link TelegramCommandDispatcher}.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface TelegramCommand {

    /** The command including its leading slash, e.g. {@code "/start"}. Matching is case-insensitive. */
    String value();
}
