package io.tykalo.telegram.ratelimit;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

/**
 * One queued outgoing Telegram {@code SendMessage}, serialized to JSON on the Redis-backed outbound
 * queue (TK-173). Held as a plain record of primitives so Jackson round-trips it without relying on
 * the Telegram library's own DTO deserialization.
 *
 * <p>{@code keyboard} models only rows of callback-data inline buttons — every keyboard the bot
 * currently sends. URL/other button types are not represented; if one is ever needed it must be
 * added here. {@code id} keeps otherwise-identical messages distinct as members of the retry
 * sorted-set, and {@code attempt} counts how many delivery tries have already failed.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OutboundMessage(
        String id,
        long chatId,
        String text,
        @Nullable String parseMode,
        @Nullable List<List<Button>> keyboard,
        int attempt) {

    /** A single inline-keyboard button carrying callback data. */
    public record Button(String text, String callbackData) {
    }

    /** Builds a fresh message (attempt 0) with a random id, flattening the Telegram keyboard. */
    public static OutboundMessage of(final long chatId, final String text, final @Nullable String parseMode,
                                     final @Nullable InlineKeyboardMarkup keyboard) {
        return new OutboundMessage(UUID.randomUUID().toString(), chatId, text, parseMode, flatten(keyboard), 0);
    }

    /** A copy of this message with the attempt counter advanced, used when scheduling a retry. */
    public OutboundMessage retried() {
        return new OutboundMessage(id, chatId, text, parseMode, keyboard, attempt + 1);
    }

    /** Rebuilds the Telegram inline keyboard, or {@code null} when this message carries none. */
    public @Nullable InlineKeyboardMarkup toKeyboard() {
        if (keyboard == null) {
            return null;
        }
        final List<InlineKeyboardRow> rows = keyboard.stream()
                .map(row -> {
                    final InlineKeyboardRow out = new InlineKeyboardRow();
                    row.forEach(b -> out.add(InlineKeyboardButton.builder()
                            .text(b.text())
                            .callbackData(b.callbackData())
                            .build()));
                    return out;
                })
                .toList();
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    private static @Nullable List<List<Button>> flatten(final @Nullable InlineKeyboardMarkup keyboard) {
        if (keyboard == null) {
            return null;
        }
        return keyboard.getKeyboard().stream()
                .map(row -> row.stream()
                        .map(b -> new Button(b.getText(), b.getCallbackData()))
                        .toList())
                .toList();
    }
}
