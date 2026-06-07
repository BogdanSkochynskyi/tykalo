package io.tykalo.telegram;

import java.util.Optional;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;

/**
 * Handles an inline-keyboard button click (a Telegram {@code callback_query}).
 * {@link TelegramCommandDispatcher} discovers every {@code CallbackHandler} bean at startup and
 * consults them in registration order when an update carries a callback query, taking the first
 * non-empty result — exactly mirroring how {@link MessageHandler} covers plain text.
 *
 * <p>The returned {@link Optional} is the short toast to show via {@code answerCallbackQuery}; an
 * implementation returns {@link Optional#empty()} for callbacks it does not own, so an unclaimed
 * callback falls through to the next handler. The spinner is always dismissed by the caller, even
 * when no handler claims the callback.
 */
public interface CallbackHandler {

    Optional<String> handle(CallbackQuery callback);
}
