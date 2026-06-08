package io.tykalo.scheduling;

import io.tykalo.list.ListRenderer;
import io.tykalo.list.Task;
import io.tykalo.list.TaskViewRenderer;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Renders a single overdue-task reminder body in Telegram <b>MarkdownV2</b>: an alarm header, the
 * task's priority dot and escaped title, and how long it has been overdue. Pure view logic — it never
 * touches the database or the Telegram API. The caller pairs it with
 * {@code ListRenderer.keyboard(List.of(task))} so the existing {@code ✅} → {@code task:done} button
 * works without new wiring.
 */
@Component
public class ReminderRenderer {

    private static final String HEADER = "⏰ *Reminder* — this task is overdue:";

    /** The MarkdownV2 reminder body for {@code task}, given the sweep instant {@code now}. */
    public String render(final Task task, final Instant now) {
        final Instant dueAt = task.getDueAt().orElseThrow(
                () -> new IllegalStateException("Reminder for task without a due date: " + task.getId()));
        final String overdueFor = humanizeOverdue(Duration.between(dueAt, now));
        return "%s\n%s %s\n_overdue by %s_".formatted(HEADER,
                TaskViewRenderer.priorityEmoji(task.getPriority().orElse(null)),
                ListRenderer.escape(task.getTitle()),
                ListRenderer.escape(overdueFor));
    }

    private String humanizeOverdue(final Duration overdue) {
        final long hours = overdue.toHours();
        if (hours >= 24) {
            final long days = overdue.toDays();
            return days + (days == 1 ? " day" : " days");
        }
        if (hours >= 1) {
            return hours + (hours == 1 ? " hour" : " hours");
        }
        final long minutes = Math.max(overdue.toMinutes(), 0);
        return minutes + (minutes == 1 ? " minute" : " minutes");
    }
}
