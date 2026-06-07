package io.tykalo.telegram;

import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

/**
 * Sends and edits rich (MarkdownV2 + inline-keyboard) Telegram messages, capturing the message id
 * so callers can later edit the same message in place. This is the seam that the plain
 * {@code String}-reply path in {@link TelegramCommandDispatcher}/{@code TykaloBot} cannot cover,
 * since that path discards the sent message's id and supports no keyboard.
 *
 * <p>The real implementation is wired only when the bot is actually polling; non-polling contexts
 * (tests, tooling) get a no-op so dependent beans still construct.
 */
public interface TelegramMessageGateway {

    /**
     * Sends a new MarkdownV2 message with an optional inline keyboard.
     *
     * @return the new message id, or empty if the send was suppressed or failed
     */
    Optional<Integer> sendMarkdown(long chatId, String markdownV2, @Nullable InlineKeyboardMarkup keyboard);

    /** Edits an existing message's text and inline keyboard in place. */
    void editMarkdown(long chatId, int messageId, String markdownV2, @Nullable InlineKeyboardMarkup keyboard);

    /**
     * Answers a callback query, dismissing the loading spinner shown after an inline-button tap.
     * Telegram requires this even when there is nothing to tell the user; pass {@code text} to show
     * a short toast, or {@code null} to just dismiss the spinner.
     */
    void answerCallback(String callbackQueryId, @Nullable String text);
}
