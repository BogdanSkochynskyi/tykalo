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
     * Queues a new MarkdownV2 message with an optional (callback-only) inline keyboard for paced
     * delivery through the rate-limit queue (TK-173). Fire-and-forget: the message id is not
     * available to the caller. Use this for every outgoing message except the one case that must
     * capture the id to edit it later (see {@link #sendMarkdownDirect}).
     */
    void sendMarkdown(long chatId, String markdownV2, @Nullable InlineKeyboardMarkup keyboard);

    /**
     * Sends a new MarkdownV2 message <em>synchronously</em>, bypassing the queue, and returns the new
     * message id. This is the single exception to routing sends through the queue: the "live" list
     * message must know its id immediately so later taps can edit it in place. Such a publish is one
     * interactive send per (list, chat) and is never bursty, so it does not threaten the rate limit.
     *
     * @return the new message id, or empty if the send was suppressed or failed
     */
    Optional<Integer> sendMarkdownDirect(long chatId, String markdownV2, @Nullable InlineKeyboardMarkup keyboard);

    /**
     * Edits an existing message's text and inline keyboard in place, reporting whether the target
     * still exists. A {@link EditOutcome#MESSAGE_GONE} result tells the caller the message can never
     * be edited again (deleted, too old, or chat unreachable) so it can drop any record of it (TK-195);
     * {@link EditOutcome#FAILED} is transient and worth retrying on the next change. Callers that don't
     * track the message id may ignore the return value.
     */
    EditOutcome editMarkdown(long chatId, int messageId, String markdownV2, @Nullable InlineKeyboardMarkup keyboard);

    /**
     * Deletes a message in place — e.g. the transient add-items prompt (TK-184) once the user is done.
     * Best-effort: a message that is already gone (or too old to delete) is not an error.
     */
    void deleteMessage(long chatId, int messageId);

    /**
     * Answers a callback query, dismissing the loading spinner shown after an inline-button tap.
     * Telegram requires this even when there is nothing to tell the user; pass {@code text} to show
     * a short toast, or {@code null} to just dismiss the spinner.
     */
    void answerCallback(String callbackQueryId, @Nullable String text);
}
