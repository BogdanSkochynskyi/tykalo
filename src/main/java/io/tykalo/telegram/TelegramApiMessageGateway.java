package io.tykalo.telegram;

import io.tykalo.telegram.ratelimit.MessageQueueService;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

/**
 * Real {@link TelegramMessageGateway} backed by a {@link TelegramClient}, using the MarkdownV2
 * parse mode. New messages are queued through {@link MessageQueueService} so the rate-limit worker
 * paces them (TK-173); edits, callback answers, and the one id-capturing publish go straight to the
 * API. Telegram errors on the direct paths are caught and logged rather than propagated: an edit that
 * fails because the message was deleted or is unchanged ("message is not modified") must not break the
 * surrounding command flow.
 */
@Slf4j
public class TelegramApiMessageGateway implements TelegramMessageGateway {

    private static final String PARSE_MODE = "MarkdownV2";

    /**
     * Lower-cased substrings of Telegram 400 descriptions that mean the target message can never be
     * edited again. Kept deliberately specific so a transient or formatting 400 (e.g. "can't parse
     * entities") never causes a still-valid {@code list_messages} row to be dropped.
     */
    private static final List<String> GONE_MARKERS = List.of(
            "message to edit not found",
            "message to be edited not found",
            "message can't be edited",
            "message_id_invalid",
            "message identifier is not specified",
            "message is too old");

    private final TelegramClient telegramClient;
    private final MessageQueueService messageQueue;

    public TelegramApiMessageGateway(final TelegramClient telegramClient, final MessageQueueService messageQueue) {
        this.telegramClient = telegramClient;
        this.messageQueue = messageQueue;
    }

    @Override
    public void sendMarkdown(final long chatId, final String markdownV2,
                             final @Nullable InlineKeyboardMarkup keyboard) {
        messageQueue.enqueue(chatId, markdownV2, PARSE_MODE, keyboard);
    }

    @Override
    public Optional<Integer> sendMarkdownDirect(final long chatId, final String markdownV2,
                                                final @Nullable InlineKeyboardMarkup keyboard) {
        final SendMessage send = SendMessage.builder()
                .chatId(chatId)
                .text(markdownV2)
                .parseMode(PARSE_MODE)
                .replyMarkup(keyboard)
                .build();
        try {
            final Message sent = telegramClient.execute(send);
            return Optional.of(sent.getMessageId());
        } catch (final TelegramApiException e) {
            log.error("Failed to send list message to chat {}", chatId, e);
            return Optional.empty();
        }
    }

    @Override
    public EditOutcome editMarkdown(final long chatId, final int messageId, final String markdownV2,
                                    final @Nullable InlineKeyboardMarkup keyboard) {
        final EditMessageText edit = EditMessageText.builder()
                .chatId(chatId)
                .messageId(messageId)
                .text(markdownV2)
                .parseMode(PARSE_MODE)
                .replyMarkup(keyboard)
                .build();
        try {
            telegramClient.execute(edit);
            return EditOutcome.EDITED;
        } catch (final TelegramApiRequestException e) {
            return classifyEditFailure(chatId, messageId, e);
        } catch (final TelegramApiException e) {
            log.warn("Failed to edit message {} in chat {}", messageId, chatId, e);
            return EditOutcome.FAILED;
        }
    }

    /**
     * Maps a Telegram edit error to an {@link EditOutcome}. Only errors that prove the message can
     * never be edited again — a whitelisted 400 ("message to edit not found", "can't be edited", too
     * old) or a 403 (chat unreachable) — are {@link EditOutcome#MESSAGE_GONE}; everything else,
     * including "message is not modified", rate limits and parse errors, stays
     * {@link EditOutcome#FAILED} so a transient or formatting fault never drops a still-valid record.
     */
    private EditOutcome classifyEditFailure(final long chatId, final int messageId,
                                            final TelegramApiRequestException e) {
        if (isMessageGone(e)) {
            log.info("Live message {} in chat {} is gone ({}): dropping its record",
                    messageId, chatId, e.getApiResponse());
            return EditOutcome.MESSAGE_GONE;
        }
        log.warn("Failed to edit message {} in chat {}: [{}] {}",
                messageId, chatId, e.getErrorCode(), e.getApiResponse());
        return EditOutcome.FAILED;
    }

    private boolean isMessageGone(final TelegramApiRequestException e) {
        final Integer code = e.getErrorCode();
        if (code != null && code == 403) {
            return true;
        }
        if (code == null || code != 400) {
            return false;
        }
        final String description = e.getApiResponse();
        if (description == null) {
            return false;
        }
        final String lower = description.toLowerCase(Locale.ROOT);
        return GONE_MARKERS.stream().anyMatch(lower::contains);
    }

    @Override
    public void deleteMessage(final long chatId, final int messageId) {
        final DeleteMessage delete = DeleteMessage.builder()
                .chatId(chatId)
                .messageId(messageId)
                .build();
        try {
            telegramClient.execute(delete);
        } catch (final TelegramApiException e) {
            log.warn("Failed to delete message {} in chat {}", messageId, chatId, e);
        }
    }

    @Override
    public void answerCallback(final String callbackQueryId, final @Nullable String text) {
        final AnswerCallbackQuery answer = AnswerCallbackQuery.builder()
                .callbackQueryId(callbackQueryId)
                .text(text)
                .build();
        try {
            telegramClient.execute(answer);
        } catch (final TelegramApiException e) {
            log.warn("Failed to answer callback query {}", callbackQueryId, e);
        }
    }
}
