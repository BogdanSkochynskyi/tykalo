package io.tykalo.nudger;

import io.tykalo.list.ListRenderer;
import io.tykalo.list.Task;
import io.tykalo.list.TaskViewRenderer;
import io.tykalo.user.User;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Renders a single escalation message in Telegram <b>MarkdownV2</b>, addressed to a nudger about an
 * owner's overdue task. The body grows with the level's {@link RevealField}: {@code NUMBER} discloses
 * only a content-free task reference (the social pressure without the content), {@code TITLE} adds the
 * task title, and {@code DESCRIPTION} adds the full description. Every level names the owner so the
 * nudger knows whom to nudge. Pure view logic — it never touches the database or the Telegram API.
 *
 * <p>"Number" is the task's short id prefix rather than a per-user sequential number, which the data
 * model does not carry; it still gives owner and nudger a shared, content-free token to reference.
 */
@Component
public class EscalationRenderer {

    private static final String HEADER = "👁 *A task needs a nudge*";

    /**
     * The MarkdownV2 escalation body for {@code task} (owned by {@code owner}), disclosing as much as
     * {@code reveal} allows, given the sweep instant {@code now}.
     */
    public String render(final User owner, final Task task, final RevealField reveal, final Instant now) {
        final Instant dueAt = task.getDueAt().orElseThrow(
                () -> new IllegalStateException("Escalation for task without a due date: " + task.getId()));
        final String overdueFor = humanizeOverdue(Duration.between(dueAt, now));
        final StringBuilder body = new StringBuilder(HEADER)
                .append("\n").append(ListRenderer.escape(ownerHandle(owner)))
                .append(" has a task overdue by ").append(ListRenderer.escape(overdueFor)).append('.');
        appendReveal(body, task, reveal);
        return body.toString();
    }

    private void appendReveal(final StringBuilder body, final Task task, final RevealField reveal) {
        switch (reveal) {
            case NUMBER -> body.append("\nTask `#").append(shortRef(task)).append('`');
            case TITLE -> body.append('\n').append(taskLine(task));
            case DESCRIPTION -> {
                body.append('\n').append(taskLine(task));
                task.getDescription().filter(d -> !d.isBlank())
                        .ifPresent(d -> body.append("\n_").append(ListRenderer.escape(d)).append('_'));
            }
        }
    }

    private String taskLine(final Task task) {
        return "%s %s".formatted(
                TaskViewRenderer.priorityEmoji(task.getPriority().orElse(null)),
                ListRenderer.escape(task.getTitle()));
    }

    private String ownerHandle(final User owner) {
        final String username = owner.getTgUsername();
        return username == null || username.isBlank() ? "Your friend" : "@" + username;
    }

    private String shortRef(final Task task) {
        return task.getId().toString().substring(0, 8);
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
