package io.tykalo.telegram;

import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

/**
 * Real {@link TelegramMessageGateway} backed by a {@link TelegramClient}, using the MarkdownV2
 * parse mode. Telegram errors are caught and logged rather than propagated: an edit that fails
 * because the message was deleted or is unchanged ("message is not modified") must not break the
 * surrounding command flow.
 */
@Slf4j
public class TelegramApiMessageGateway implements TelegramMessageGateway {

    private static final String PARSE_MODE = "MarkdownV2";

    private final TelegramClient telegramClient;

    public TelegramApiMessageGateway(final TelegramClient telegramClient) {
        this.telegramClient = telegramClient;
    }

    @Override
    public Optional<Integer> sendMarkdown(final long chatId, final String markdownV2,
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
    public void editMarkdown(final long chatId, final int messageId, final String markdownV2,
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
        } catch (final TelegramApiException e) {
            log.warn("Failed to edit list message {} in chat {}", messageId, chatId, e);
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
