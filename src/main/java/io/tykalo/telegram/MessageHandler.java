package io.tykalo.telegram;

import java.util.Optional;
import org.telegram.telegrambots.meta.api.objects.Update;

/**
 * Handles a plain text message — one whose text does not start with {@code /} and therefore
 * carries no command. {@link TelegramCommandDispatcher} discovers every {@code MessageHandler}
 * bean at startup and consults them in registration order when an update has no command, sending
 * the first non-empty reply.
 *
 * <p>Implementations return {@link Optional#empty()} for messages they do not own, so a message
 * no handler claims leaves the bot silent — the same behaviour as an unrecognised command.
 */
public interface MessageHandler {

    Optional<String> handle(Update update);
}
