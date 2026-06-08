package io.tykalo.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import io.tykalo.list.Priority;
import io.tykalo.list.Task;
import io.tykalo.list.TaskList;
import io.tykalo.user.User;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReminderRendererTest {

    private static final Instant DUE = Instant.parse("2026-06-08T00:00:00Z");

    private final ReminderRenderer renderer = new ReminderRenderer();

    private static Task task(final String title) {
        final User owner = new User();
        owner.setId(UUID.randomUUID());
        final TaskList list = TaskList.project(owner, "Launch");
        list.setId(UUID.randomUUID());
        final Task task = Task.create(list, title);
        task.setId(UUID.randomUUID());
        task.setDueAt(DUE);
        return task;
    }

    @Test
    void render_includesHeaderTitleAndOverdueDuration() {
        // Arrange
        final Task task = task("Ship it");

        // Act — 3 hours past due
        final String body = renderer.render(task, DUE.plusSeconds(3 * 3600));

        // Assert
        assertThat(body)
                .startsWith("⏰ *Reminder* — this task is overdue:")
                .contains("Ship it")
                .contains("overdue by 3 hours");
    }

    @Test
    void render_escapesMarkdownSpecialsInTitle() {
        // Arrange — title with MarkdownV2 reserved characters
        final Task task = task("Pay-rent (now!)");

        // Act
        final String body = renderer.render(task, DUE.plusSeconds(2 * 3600));

        // Assert — '-', '(', ')', '!' are backslash-escaped
        assertThat(body).contains("Pay\\-rent \\(now\\!\\)");
    }

    @Test
    void render_singularizesOneHour() {
        assertThat(renderer.render(task("x"), DUE.plusSeconds(3600))).contains("overdue by 1 hour");
    }

    @Test
    void render_usesMinutes_underAnHour() {
        assertThat(renderer.render(task("x"), DUE.plusSeconds(150 * 60))).contains("overdue by 2 hours");
        assertThat(renderer.render(task("x"), DUE.plusSeconds(30 * 60))).contains("overdue by 30 minutes");
    }

    @Test
    void render_usesDays_pastTwentyFourHours() {
        assertThat(renderer.render(task("x"), DUE.plusSeconds(50 * 3600))).contains("overdue by 2 days");
    }
}
