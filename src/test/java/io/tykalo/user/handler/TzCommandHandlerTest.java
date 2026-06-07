package io.tykalo.user.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
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
class TzCommandHandlerTest {

    @Mock
    private UserService userService;

    @InjectMocks
    private TzCommandHandler handler;

    @Test
    void tz_showsCurrentZone_whenNoArgument() {
        // Arrange
        final Update update = TelegramUpdateFixtures.command("/tz", 42L, "bob", "uk");
        when(userService.findOrCreate(update))
                .thenReturn(User.create(42L, "bob", ZoneId.of("Europe/Kyiv"), "uk"));

        // Act
        final String reply = handler.tz(update);

        // Assert
        assertThat(reply).contains("Europe/Kyiv");
        verify(userService, never()).updateTimezone(any(), any());
    }

    @Test
    void tz_showsUtc_whenZoneUnset() {
        // Arrange
        final Update update = TelegramUpdateFixtures.command("/tz", 42L, "bob", "uk");
        when(userService.findOrCreate(update)).thenReturn(User.create(42L, "bob", null, "uk"));

        // Act
        final String reply = handler.tz(update);

        // Assert
        assertThat(reply).contains("UTC");
    }

    @Test
    void tz_overridesZone_whenValidIana() {
        // Arrange
        final Update update = TelegramUpdateFixtures.command("/tz Europe/Warsaw", 42L, "bob", "uk");
        final User user = User.create(42L, "bob", ZoneId.of("Europe/Kyiv"), "uk");
        when(userService.findOrCreate(update)).thenReturn(user);

        // Act
        final String reply = handler.tz(update);

        // Assert
        verify(userService).updateTimezone(user, ZoneId.of("Europe/Warsaw"));
        assertThat(reply).contains("Europe/Warsaw");
    }

    @Test
    void tz_rejectsInvalidIana_withoutPersisting() {
        // Arrange
        final Update update = TelegramUpdateFixtures.command("/tz Mars/Phobos", 42L, "bob", "uk");
        when(userService.findOrCreate(update))
                .thenReturn(User.create(42L, "bob", ZoneId.of("Europe/Kyiv"), "uk"));

        // Act
        final String reply = handler.tz(update);

        // Assert
        assertThat(reply).contains("isn't a valid IANA timezone").contains("Mars/Phobos");
        verify(userService, never()).updateTimezone(any(), any());
    }
}
