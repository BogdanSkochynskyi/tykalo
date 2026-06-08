package io.tykalo.scheduling;

import io.tykalo.list.ListRenderer;
import io.tykalo.list.Task;
import io.tykalo.list.TaskViewRenderer;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Renders the morning digest body in Telegram <b>MarkdownV2</b>. The digest is a flat, numbered list
 * of the day's {@code PROJECT} tasks, each prefixed with a priority dot and suffixed with its local
 * due time. Numbering matches {@link ListRenderer#keyboard(List)} (1..N in list order), so inline
 * {@code ✅ N} buttons line up with the rendered lines.
 *
 * <p>Pure view logic — it never touches the database or the Telegram API. The caller passes tasks
 * already ordered by due time and the user's zone for the wall-clock rendering.
 */
@Component
public class MorningDigestRenderer {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);
    private static final String HEADER = "☀️ *Good morning\\!* Your project tasks for today:";

    /** The MarkdownV2 digest body for {@code tasks}, timed in {@code zone}. Tasks must be non-empty. */
    public String render(final List<Task> tasks, final ZoneId zone) {
        final StringBuilder out = new StringBuilder(HEADER);
        int index = 1;
        for (final Task task : tasks) {
            out.append('\n').append(line(index++, task, zone));
        }
        return out.toString();
    }

    private String line(final int index, final Task task, final ZoneId zone) {
        final Instant dueAt = task.getDueAt().orElseThrow(
                () -> new IllegalStateException("Digest task without a due date: " + task.getId()));
        final String time = TIME.format(dueAt.atZone(zone));
        return "%d\\. %s %s — %s".formatted(index,
                TaskViewRenderer.priorityEmoji(task.getPriority().orElse(null)),
                ListRenderer.escape(task.getTitle()),
                ListRenderer.escape(time));
    }
}
