package io.tykalo.user.handler;

import static org.assertj.core.api.Assertions.assertThat;

import io.tykalo.telegram.TelegramUpdateFixtures;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.objects.Update;

class HelpCommandHandlerTest {

    private final HelpCommandHandler handler = new HelpCommandHandler();

    @Test
    void help_groupsEveryCommandCategory() {
        // Arrange
        final Update update = TelegramUpdateFixtures.command("/help", 42L, "bob", "uk");

        // Act
        final String reply = handler.help(update);

        // Assert
        assertThat(reply)
                .contains("Lists")
                .contains("Tasks")
                .contains("Nudgers")
                .contains("Scheduling")
                .contains("Settings");
    }

    @Test
    void help_listsRepresentativeCommandsWithExamples() {
        // Arrange
        final Update update = TelegramUpdateFixtures.command("/help", 42L, "bob", "uk");

        // Act
        final String reply = handler.help(update);

        // Assert
        assertThat(reply)
                .contains("/add")
                .contains("/lists")
                .contains("/done")
                .contains("/nudgers add @username")
                .contains("/task")
                .contains("/morning")
                .contains("/tz")
                .contains("/quiet")
                .contains("e.g.");
    }
}
