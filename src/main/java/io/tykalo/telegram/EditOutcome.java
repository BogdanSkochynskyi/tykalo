package io.tykalo.telegram;

/**
 * The result of an in-place message edit, letting the caller decide whether the record it keeps for
 * that message is still worth holding onto. Returned by
 * {@link TelegramMessageGateway#editMarkdown}.
 *
 * <p>The distinction matters for the "live" list message (TK-195): a list mirrored in several chats
 * is edited once per chat on every change, and a chat whose message has been deleted (or has aged
 * past Telegram's 48h edit window) must have its {@code list_messages} row dropped so the bot stops
 * trying to edit a message that can never succeed again.
 */
public enum EditOutcome {

    /** The message was edited, or was already up to date ("not modified") — it still exists. */
    EDITED,

    /**
     * The message can no longer be edited — it was deleted, is too old, or the chat is unreachable.
     * The caller should drop any record pointing at it; retrying will never succeed.
     */
    MESSAGE_GONE,

    /**
     * A transient or unclassified failure (rate limit, server error, network, malformed payload).
     * The message may still exist, so the caller should keep its record and retry on the next change.
     */
    FAILED
}
