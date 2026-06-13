package io.tykalo.telegram.conversation;

import java.util.Optional;
import org.telegram.telegrambots.meta.api.objects.Update;

/**
 * Handles a plain-text message while the user is in an input-expecting {@link ConversationState}
 * (see {@link ConversationState#expectsTextInput()}). {@link io.tykalo.telegram.TelegramCommandDispatcher}
 * discovers every {@code StateHandler} bean at startup and, for such a message, routes it to the first
 * handler whose {@link #canHandle(ConversationState)} returns {@code true} — bypassing the ordinary
 * {@link io.tykalo.telegram.MessageHandler} chain so guided-flow input is never mistaken for chatter.
 *
 * <p>This is the extension point the menu screens (TK-181–186) build on; the foundation ticket
 * (TK-187) ships it with no implementations, so until a screen registers one every message still flows
 * through the normal handlers. A claiming handler owns the message: its result is returned as-is, even
 * when empty, so no fall-through happens once {@link #canHandle} has matched.
 */
public interface StateHandler {

    /** Whether this handler owns the given input-expecting state. */
    boolean canHandle(ConversationState state);

    /** Consumes the message for {@code state}, returning the reply text if one should be sent. */
    Optional<String> handle(Update update, ConversationState state);
}
