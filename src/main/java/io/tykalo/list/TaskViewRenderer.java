package io.tykalo.list;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Formats task views ({@code /today}, {@code /overdue}, {@code /week}) into plain-text Telegram
 * replies. Pure view logic — it never touches the database or the Telegram API; the caller resolves
 * tasks and list names and passes the user's zone so every due time renders in local wall-clock.
 *
 * <p>Output is plain text (no Markdown syntax): the string-reply path sends without a parse mode,
 * so structure is carried by emoji and line breaks rather than {@code *bold*}. Each task line is
 * prefixed with a priority emoji — {@code 🔴 URGENT / 🟠 HIGH / 🟡 MEDIUM / ⚪ LOW}, and an unset
 * priority renders as {@code ⚪} too. {@code /today} and {@code /overdue} group by list; {@code /week}
 * groups by day. View queries only return tasks that have a {@code dueAt}, so it is always present.
 */
@Component
public class TaskViewRenderer {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter DAY_HEADER = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.ENGLISH);
    private static final String UNKNOWN_LIST = "Unknown list";

    /** Tasks due during the user's local day, grouped by list and timed {@code HH:mm}. */
    public String today(final List<Task> tasks, final Map<UUID, String> listNames, final ZoneId zone) {
        if (tasks.isEmpty()) {
            return "📅 Nothing due today.";
        }
        final StringBuilder out = new StringBuilder("📅 Today");
        appendGroupedByList(out, tasks, listNames, zone, TIME);
        return out.toString();
    }

    /** Tasks past their due date, grouped by list; each shows the full {@code MMM d, HH:mm} stamp. */
    public String overdue(final List<Task> tasks, final Map<UUID, String> listNames, final ZoneId zone) {
        if (tasks.isEmpty()) {
            return "🎉 No overdue tasks.";
        }
        final StringBuilder out = new StringBuilder("⏰ Overdue");
        appendGroupedByList(out, tasks, listNames, zone, DATE_TIME);
        return out.toString();
    }

    /** Tasks due over the next seven days, grouped by day in chronological order. */
    public String week(final List<Task> tasks, final ZoneId zone) {
        if (tasks.isEmpty()) {
            return "📅 Nothing due in the next 7 days.";
        }
        final StringBuilder out = new StringBuilder("🗓 This week");
        final Map<LocalDate, List<Task>> byDay = tasks.stream()
                .sorted(byDueDate())
                .collect(Collectors.groupingBy(
                        task -> dueAt(task).atZone(zone).toLocalDate(),
                        LinkedHashMap::new, Collectors.toList()));
        byDay.forEach((day, dayTasks) -> {
            out.append("\n\n").append(DAY_HEADER.format(day));
            dayTasks.forEach(task -> out.append('\n').append(line(task, zone, TIME)));
        });
        return out.toString();
    }

    private void appendGroupedByList(final StringBuilder out, final List<Task> tasks,
                                     final Map<UUID, String> listNames, final ZoneId zone,
                                     final DateTimeFormatter timeFormat) {
        final Map<UUID, List<Task>> byList = tasks.stream()
                .collect(Collectors.groupingBy(Task::getListId, LinkedHashMap::new, Collectors.toList()));
        byList.entrySet().stream()
                .sorted(Comparator.comparing(entry -> listName(listNames, entry.getKey()),
                        String.CASE_INSENSITIVE_ORDER))
                .forEach(entry -> {
                    out.append("\n\n📋 ").append(listName(listNames, entry.getKey()));
                    entry.getValue().stream()
                            .sorted(byDueDate())
                            .forEach(task -> out.append('\n').append(line(task, zone, timeFormat)));
                });
    }

    private String line(final Task task, final ZoneId zone, final DateTimeFormatter timeFormat) {
        final String when = timeFormat.format(dueAt(task).atZone(zone));
        return "%s %s — %s".formatted(priorityEmoji(task.getPriority().orElse(null)), task.getTitle(), when);
    }

    private static Comparator<Task> byDueDate() {
        return Comparator.comparing(TaskViewRenderer::dueAt).thenComparing(Task::getTitle);
    }

    private static Instant dueAt(final Task task) {
        return task.getDueAt().orElseThrow(
                () -> new IllegalStateException("View task without a due date: " + task.getId()));
    }

    private static String listName(final Map<UUID, String> listNames, final UUID listId) {
        return listNames.getOrDefault(listId, UNKNOWN_LIST);
    }

    /** Priority → colour dot; {@code null} (no priority set) renders the same neutral dot as LOW. */
    public static String priorityEmoji(final @Nullable Priority priority) {
        if (priority == null) {
            return "⚪";
        }
        return switch (priority) {
            case URGENT -> "🔴";
            case HIGH -> "🟠";
            case MEDIUM -> "🟡";
            case LOW -> "⚪";
        };
    }
}
