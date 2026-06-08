package io.tykalo.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import io.tykalo.list.Priority;
import io.tykalo.list.Task;
import io.tykalo.list.TaskList;
import io.tykalo.user.User;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MorningDigestRendererTest {

    private final MorningDigestRenderer renderer = new MorningDigestRenderer();

    private static Task task(final String title, final Instant dueAt, final Priority priority) {
        final User owner = new User();
        owner.setId(UUID.randomUUID());
        final TaskList list = TaskList.project(owner, "Launch");
        list.setId(UUID.randomUUID());
        final Task task = Task.create(list, title);
        task.setId(UUID.randomUUID());
        task.setDueAt(dueAt);
        task.setPriority(priority);
        return task;
    }

    @Test
    void render_numbersTasks_andRendersLocalTimeWithPriorityDot() {
        // Arrange
        final List<Task> tasks = List.of(
                task("Ship", Instant.parse("2026-06-08T07:30:00Z"), Priority.URGENT),
                task("Review", Instant.parse("2026-06-08T13:00:00Z"), null));

        // Act
        final String body = renderer.render(tasks, ZoneOffset.UTC);

        // Assert
        assertThat(body).startsWith("☀️ *Good morning\\!*");
        assertThat(body).contains("1\\. 🔴 Ship — 07:30");
        assertThat(body).contains("2\\. ⚪ Review — 13:00");
    }

    @Test
    void render_rendersTimeInUsersZone() {
        // Arrange — in June Kyiv is UTC+3 (DST), so 07:30Z renders as 10:30 local
        final List<Task> tasks = List.of(task("Ship", Instant.parse("2026-06-08T07:30:00Z"), Priority.LOW));

        // Act
        final String body = renderer.render(tasks, ZoneId.of("Europe/Kyiv"));

        // Assert
        assertThat(body).contains("10:30");
    }

    @Test
    void render_escapesMarkdownV2SpecialCharactersInTitle() {
        // Arrange
        final List<Task> tasks = List.of(task("Fix bug (urgent)!", Instant.parse("2026-06-08T07:00:00Z"), null));

        // Act
        final String body = renderer.render(tasks, ZoneOffset.UTC);

        // Assert
        assertThat(body).contains("Fix bug \\(urgent\\)\\!");
    }
}
