package io.tykalo.list;

import static org.assertj.core.api.Assertions.assertThat;

import io.tykalo.user.User;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TaskViewRendererTest {

    private static final ZoneId KYIV = ZoneId.of("Europe/Kyiv");

    private final TaskViewRenderer renderer = new TaskViewRenderer();

    private final User owner = persistedOwner();

    private static User persistedOwner() {
        final User user = User.create(1L, "owner", KYIV, "uk");
        user.setId(UUID.randomUUID());
        return user;
    }

    private TaskList list(final String name) {
        final TaskList list = TaskList.checklist(owner, name);
        list.setId(UUID.randomUUID());
        return list;
    }

    private Task task(final TaskList list, final String title, final Instant dueAt,
                      final Priority priority) {
        final Task task = Task.create(list, title);
        task.setId(UUID.randomUUID());
        task.setDueAt(dueAt);
        task.setPriority(priority);
        return task;
    }

    @Test
    void today_groupsByList_andPrefixesPriorityEmojiWithLocalTime() {
        // Arrange
        final TaskList work = list("Work");
        final TaskList inbox = list("Inbox");
        final Task report = task(work, "Submit report", Instant.parse("2026-06-07T11:00:00Z"), Priority.URGENT);
        final Task email = task(work, "Email client", Instant.parse("2026-06-07T14:30:00Z"), Priority.MEDIUM);
        final Task milk = task(inbox, "Buy milk", Instant.parse("2026-06-07T16:00:00Z"), null);
        final Map<UUID, String> names = Map.of(work.getId(), "Work", inbox.getId(), "Inbox");

        // Act
        final String out = renderer.today(List.of(report, email, milk), names, KYIV);

        // Assert
        assertThat(out)
                .startsWith("📅 Today")
                .contains("📋 Work")
                .contains("📋 Inbox")
                .contains("🔴 Submit report — 14:00")
                .contains("🟡 Email client — 17:30")
                .contains("⚪ Buy milk — 19:00");
        // Lists are ordered alphabetically (Inbox before Work); within a list, by due time.
        assertThat(out.indexOf("Inbox")).isLessThan(out.indexOf("Work"));
        assertThat(out.indexOf("Submit report")).isLessThan(out.indexOf("Email client"));
    }

    @Test
    void today_reportsEmptyState_whenNoTasks() {
        assertThat(renderer.today(List.of(), Map.of(), KYIV)).isEqualTo("📅 Nothing due today.");
    }

    @Test
    void overdue_showsFullDateStamp_groupedByList() {
        // Arrange
        final TaskList work = list("Work");
        final Task task = task(work, "Old task", Instant.parse("2026-06-05T07:00:00Z"), Priority.HIGH);
        final Map<UUID, String> names = Map.of(work.getId(), "Work");

        // Act
        final String out = renderer.overdue(List.of(task), names, KYIV);

        // Assert
        assertThat(out)
                .startsWith("⏰ Overdue")
                .contains("📋 Work")
                .contains("🟠 Old task — Jun 5, 10:00");
    }

    @Test
    void overdue_reportsEmptyState_whenNoTasks() {
        assertThat(renderer.overdue(List.of(), Map.of(), KYIV)).isEqualTo("🎉 No overdue tasks.");
    }

    @Test
    void week_groupsByDay_inChronologicalOrder() {
        // Arrange
        final TaskList work = list("Work");
        final Task sunday = task(work, "Sunday early", Instant.parse("2026-06-07T11:00:00Z"), Priority.LOW);
        final Task sundayLate = task(work, "Sunday late", Instant.parse("2026-06-07T16:00:00Z"), Priority.LOW);
        final Task tuesday = task(work, "Tuesday task", Instant.parse("2026-06-09T06:00:00Z"), Priority.LOW);

        // Act — order of input is deliberately not chronological
        final String out = renderer.week(List.of(tuesday, sundayLate, sunday), KYIV);

        // Assert
        assertThat(out)
                .startsWith("🗓 This week")
                .contains("Jun 7")
                .contains("Jun 9")
                .contains("⚪ Sunday early — 14:00")
                .contains("⚪ Tuesday task — 09:00");
        // Days are chronological, and within a day tasks are ordered by time.
        assertThat(out.indexOf("Jun 7")).isLessThan(out.indexOf("Jun 9"));
        assertThat(out.indexOf("Sunday early")).isLessThan(out.indexOf("Sunday late"));
    }

    @Test
    void week_reportsEmptyState_whenNoTasks() {
        assertThat(renderer.week(List.of(), KYIV)).isEqualTo("📅 Nothing due in the next 7 days.");
    }

    @Test
    void priorityEmoji_mapsEachPriority_andTreatsUnsetAsNeutral() {
        assertThat(TaskViewRenderer.priorityEmoji(Priority.URGENT)).isEqualTo("🔴");
        assertThat(TaskViewRenderer.priorityEmoji(Priority.HIGH)).isEqualTo("🟠");
        assertThat(TaskViewRenderer.priorityEmoji(Priority.MEDIUM)).isEqualTo("🟡");
        assertThat(TaskViewRenderer.priorityEmoji(Priority.LOW)).isEqualTo("⚪");
        assertThat(TaskViewRenderer.priorityEmoji(null)).isEqualTo("⚪");
    }
}
