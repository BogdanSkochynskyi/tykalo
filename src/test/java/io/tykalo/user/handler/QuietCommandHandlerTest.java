package io.tykalo.user.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.tykalo.telegram.TelegramUpdateFixtures;
import io.tykalo.user.User;
import io.tykalo.user.UserService;
import java.time.LocalTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.objects.Update;

@ExtendWith(MockitoExtension.class)
class QuietCommandHandlerTest {

    @Mock
    private UserService userService;

    @InjectMocks
    private QuietCommandHandler handler;

    private static User user() {
        return User.create(42L, "bob", ZoneId.of("Europe/Kyiv"), "uk");
    }

    @Test
    void quiet_showsCurrentWindow_whenNoArgument() {
        // Arrange
        final Update update = TelegramUpdateFixtures.command("/quiet", 42L, "bob", "uk");
        when(userService.findOrCreate(update)).thenReturn(user());

        // Act
        final String reply = handler.quiet(update);

        // Assert — default window from User.create is 22:00–07:00
        assertThat(reply).contains("22:00").contains("07:00");
        verify(userService, never()).updateQuietHours(any(), any(), any());
    }

    @Test
    void quiet_showsOff_whenWindowUnset() {
        // Arrange
        final Update update = TelegramUpdateFixtures.command("/quiet", 42L, "bob", "uk");
        final User user = user();
        user.setQuietHoursStart(null);
        user.setQuietHoursEnd(null);
        when(userService.findOrCreate(update)).thenReturn(user);

        // Act
        final String reply = handler.quiet(update);

        // Assert
        assertThat(reply).contains("off");
    }

    @Test
    void quiet_setsWindow_whenValidPeriod() {
        // Arrange
        final Update update = TelegramUpdateFixtures.command("/quiet 22:00-07:00", 42L, "bob", "uk");
        final User user = user();
        when(userService.findOrCreate(update)).thenReturn(user);

        // Act
        final String reply = handler.quiet(update);

        // Assert
        verify(userService).updateQuietHours(user, LocalTime.of(22, 0), LocalTime.of(7, 0));
        assertThat(reply).contains("22:00").contains("07:00");
    }

    @Test
    void quiet_setsWindow_whenSingleDigitHours() {
        // Arrange
        final Update update = TelegramUpdateFixtures.command("/quiet 9:30-17:00", 42L, "bob", "uk");
        final User user = user();
        when(userService.findOrCreate(update)).thenReturn(user);

        // Act
        final String reply = handler.quiet(update);

        // Assert
        verify(userService).updateQuietHours(user, LocalTime.of(9, 30), LocalTime.of(17, 0));
        assertThat(reply).contains("09:30").contains("17:00");
    }

    @Test
    void quiet_disablesWindow_whenOff() {
        // Arrange
        final Update update = TelegramUpdateFixtures.command("/quiet off", 42L, "bob", "uk");
        final User user = user();
        when(userService.findOrCreate(update)).thenReturn(user);

        // Act
        final String reply = handler.quiet(update);

        // Assert
        verify(userService).disableQuietHours(user);
        assertThat(reply).contains("off");
    }

    @Test
    void quiet_rejectsInvalidFormat_withoutPersisting() {
        // Arrange
        final Update update = TelegramUpdateFixtures.command("/quiet 25:00-07:00", 42L, "bob", "uk");
        when(userService.findOrCreate(update)).thenReturn(user());

        // Act
        final String reply = handler.quiet(update);

        // Assert
        assertThat(reply).contains("HH:MM");
        verify(userService, never()).updateQuietHours(any(), any(), any());
    }

    @Test
    void quiet_rejectsEqualBounds_withoutPersisting() {
        // Arrange
        final Update update = TelegramUpdateFixtures.command("/quiet 22:00-22:00", 42L, "bob", "uk");
        when(userService.findOrCreate(update)).thenReturn(user());

        // Act
        final String reply = handler.quiet(update);

        // Assert
        assertThat(reply).contains("same time");
        verify(userService, never()).updateQuietHours(any(), any(), any());
    }
}
