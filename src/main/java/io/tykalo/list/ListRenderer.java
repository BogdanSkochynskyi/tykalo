package io.tykalo.list;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

/**
 * Renders a list's tasks into the body of the "live" Telegram message and the matching inline
 * keyboard. Output is Telegram <b>MarkdownV2</b> — chosen over legacy Markdown because it supports
 * {@code ~strikethrough~} for completed tasks; every interpolated value is escaped accordingly.
 *
 * <p>This is pure view logic: it never touches the database or the Telegram API. The list-name
 * header is added by {@link ListMessageService}; this class only owns the task lines and buttons.
 */
@Component
public class ListRenderer {

    private static final String MARKDOWN_V2_SPECIAL = "_*[]()~`>#+-=|{}.!\\";
    private static final int BUTTONS_PER_ROW = 5;

    /** The numbered task body in MarkdownV2; DONE tasks are struck through. */
    public String render(final List<Task> tasks) {
        if (tasks.isEmpty()) {
            return "_No tasks yet\\._";
        }
        final StringBuilder body = new StringBuilder();
        int index = 1;
        for (final Task task : tasks) {
            body.append(renderLine(index++, task)).append('\n');
        }
        return body.toString().stripTrailing();
    }

    /** One {@code ✅ N} button per task, carrying {@code callback_data = task:done:{taskId}}. */
    public InlineKeyboardMarkup keyboard(final List<Task> tasks) {
        final List<InlineKeyboardRow> rows = new ArrayList<>();
        InlineKeyboardRow row = new InlineKeyboardRow();
        int index = 1;
        for (final Task task : tasks) {
            row.add(doneButton(index++, task));
            if (row.size() == BUTTONS_PER_ROW) {
                rows.add(row);
                row = new InlineKeyboardRow();
            }
        }
        if (!row.isEmpty()) {
            rows.add(row);
        }
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    private String renderLine(final int index, final Task task) {
        final String title = escape(task.getTitle());
        if (task.getStatus() == TaskStatus.DONE) {
            return "%d\\. ~%s~".formatted(index, title);
        }
        return "%d\\. %s".formatted(index, title);
    }

    private InlineKeyboardButton doneButton(final int index, final Task task) {
        final UUID id = Objects.requireNonNull(task.getId(), "task must be persisted before rendering");
        return InlineKeyboardButton.builder()
                .text("✅ " + index)
                .callbackData("task:done:" + id)
                .build();
    }

    static String escape(final String text) {
        final StringBuilder escaped = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (MARKDOWN_V2_SPECIAL.indexOf(c) >= 0) {
                escaped.append('\\');
            }
            escaped.append(c);
        }
        return escaped.toString();
    }
}
