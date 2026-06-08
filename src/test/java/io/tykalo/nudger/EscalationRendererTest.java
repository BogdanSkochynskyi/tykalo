package io.tykalo.nudger;

import static org.assertj.core.api.Assertions.assertThat;

import io.tykalo.list.Task;
import io.tykalo.list.TaskList;
import io.tykalo.user.User;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

class EscalationRendererTest {

    private static final Instant DUE = Instant.parse("2026-06-08T00:00:00Z");

    private final EscalationRenderer renderer = new EscalationRenderer();

    private static User owner(final String username) {
        final User user = User.create(1L, username, ZoneOffset.UTC, "en");
        user.setId(UUID.randomUUID());
        return user;
    }

    private static Task task(final String title, final String description) {
        final TaskList list = TaskList.project(owner("ownerHandle"), "Launch");
        list.setId(UUID.randomUUID());
        final Task task = Task.create(list, title);
        task.setId(UUID.randomUUID());
        task.setDueAt(DUE);
        task.setDescription(description);
        return task;
    }

    @Test
    void render_number_revealsOwnerAndReferenceButNotTitle() {
        // Arrange
        final User owner = owner("bob");
        final Task task = task("Secret launch plan", "do the thing");

        // Act
        final String body = renderer.render(owner, task, RevealField.NUMBER, DUE.plus(Duration.ofHours(2)));

        // Assert — names the owner and a content-free reference, but never the title or description
        assertThat(body).contains("@bob");
        assertThat(body).contains("#" + task.getId().toString().substring(0, 8));
        assertThat(body).doesNotContain("Secret launch plan");
        assertThat(body).doesNotContain("do the thing");
    }

    @Test
    void render_title_revealsTitleButNotDescription() {
        // Arrange
        final User owner = owner("bob");
        final Task task = task("Ship the release", "internal notes");

        // Act
        final String body = renderer.render(owner, task, RevealField.TITLE, DUE.plus(Duration.ofHours(6)));

        // Assert
        assertThat(body).contains("Ship the release");
        assertThat(body).doesNotContain("internal notes");
    }

    @Test
    void render_description_revealsTitleAndDescription() {
        // Arrange
        final User owner = owner("bob");
        final Task task = task("Ship the release", "deploy to prod and notify the team");

        // Act
        final String body = renderer.render(owner, task, RevealField.DESCRIPTION, DUE.plus(Duration.ofHours(12)));

        // Assert
        assertThat(body).contains("Ship the release");
        assertThat(body).contains("deploy to prod and notify the team");
    }

    @Test
    void render_escapesMarkdownV2SpecialCharacters() {
        // Arrange — a title with MarkdownV2 metacharacters must be escaped
        final User owner = owner("bob");
        final Task task = task("Fix bug (urgent)!", "x");

        // Act
        final String body = renderer.render(owner, task, RevealField.TITLE, DUE.plus(Duration.ofHours(6)));

        // Assert — parentheses and bang are backslash-escaped
        assertThat(body).contains("Fix bug \\(urgent\\)\\!");
    }

    @Test
    void render_fallsBackToGenericLabel_whenOwnerHasNoUsername() {
        // Arrange
        final User owner = owner(null);
        final Task task = task("Ship it", "x");

        // Act
        final String body = renderer.render(owner, task, RevealField.NUMBER, DUE.plus(Duration.ofHours(2)));

        // Assert
        assertThat(body).contains("Your friend");
    }

    @Test
    void ackKeyboard_buildsSingleReminderButton_carryingTheNudgeLogId() {
        // Arrange
        final UUID nudgeLogId = UUID.randomUUID();

        // Act
        final InlineKeyboardMarkup keyboard = renderer.ackKeyboard(nudgeLogId);

        // Assert — one row, one button, callback data is the ack prefix + the log id
        assertThat(keyboard.getKeyboard()).hasSize(1);
        assertThat(keyboard.getKeyboard().getFirst()).hasSize(1);
        final InlineKeyboardButton button = keyboard.getKeyboard().getFirst().getFirst();
        assertThat(button.getText()).isEqualTo("✅ I reminded");
        assertThat(button.getCallbackData()).isEqualTo("nudge:ack:" + nudgeLogId);
    }
}
