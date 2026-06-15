package io.tykalo.telegram;

import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

/**
 * No-op {@link TelegramMessageGateway} used when the bot is not polling (tests, tooling). It sends
 * nothing and reports no message id, so dependent beans construct without a live Telegram client.
 */
@Slf4j
public class NoOpTelegramMessageGateway implements TelegramMessageGateway {

    @Override
    public void sendMarkdown(final long chatId, final String markdownV2,
                             final @Nullable InlineKeyboardMarkup keyboard) {
        log.debug("Polling disabled — suppressing queued message send to chat {}", chatId);
    }

    @Override
    public Optional<Integer> sendMarkdownDirect(final long chatId, final String markdownV2,
                                                final @Nullable InlineKeyboardMarkup keyboard) {
        log.debug("Polling disabled — suppressing list message send to chat {}", chatId);
        return Optional.empty();
    }

    @Override
    public EditOutcome editMarkdown(final long chatId, final int messageId, final String markdownV2,
                                    final @Nullable InlineKeyboardMarkup keyboard) {
        log.debug("Polling disabled — suppressing list message edit {} in chat {}", messageId, chatId);
        return EditOutcome.EDITED;
    }

    @Override
    public void deleteMessage(final long chatId, final int messageId) {
        log.debug("Polling disabled — suppressing delete of message {} in chat {}", messageId, chatId);
    }

    @Override
    public void answerCallback(final String callbackQueryId, final @Nullable String text) {
        log.debug("Polling disabled — suppressing answer to callback query {}", callbackQueryId);
    }
}
