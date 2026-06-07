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
    public Optional<Integer> sendMarkdown(final long chatId, final String markdownV2,
                                          final @Nullable InlineKeyboardMarkup keyboard) {
        log.debug("Polling disabled — suppressing list message send to chat {}", chatId);
        return Optional.empty();
    }

    @Override
    public void editMarkdown(final long chatId, final int messageId, final String markdownV2,
                             final @Nullable InlineKeyboardMarkup keyboard) {
        log.debug("Polling disabled — suppressing list message edit {} in chat {}", messageId, chatId);
    }
}
