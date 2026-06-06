package io.tykalo.user.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
class StartCommandHandlerTest {

    @Mock
    private UserService userService;

    @InjectMocks
    private StartCommandHandler handler;

    @Test
    void start_registersUser_andGreetsByUsername() {
        // Arrange
        final Update update = TelegramUpdateFixtures.command("/start", 42L, "bob", "uk");
        when(userService.findOrCreate(update))
                .thenReturn(User.create(42L, "bob", ZoneId.of("Europe/Kyiv"), "uk"));

        // Act
        final String reply = handler.start(update);

        // Assert
        verify(userService).findOrCreate(update);
        assertThat(reply)
                .contains("Tykalo")
                .contains("@bob")
                .contains("Europe/Kyiv");
    }

    @Test
    void start_greetsGenerically_whenUsernameMissing() {
        final Update update = TelegramUpdateFixtures.command("/start", 42L, null, "uk");
        when(userService.findOrCreate(update))
                .thenReturn(User.create(42L, null, ZoneId.of("Europe/Kyiv"), "uk"));

        assertThat(handler.start(update)).contains("Hi, there!");
    }
}
