package io.tykalo.user.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.tykalo.menu.HelpService;
import io.tykalo.telegram.TelegramUpdateFixtures;
import io.tykalo.user.User;
import io.tykalo.user.UserService;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.objects.Update;

@ExtendWith(MockitoExtension.class)
class HelpCommandHandlerTest {

    @Mock
    private UserService userService;

    @Mock
    private HelpService helpService;

    @InjectMocks
    private HelpCommandHandler handler;

    @Test
    void help_opensTheHelpScreen_andStaysSilent() {
        // Arrange
        final Update update = TelegramUpdateFixtures.command("/help", 42L, "bob", "uk");
        final User user = User.create(42L, "bob", ZoneId.of("Europe/Kyiv"), "uk");
        when(userService.findOrCreate(update)).thenReturn(user);

        // Act
        final String reply = handler.help(update);

        // Assert — the screen is sent via the gateway, so the command itself returns nothing
        assertThat(reply).isNull();
        verify(helpService).open(user);
    }
}
