package io.tykalo.user.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
class MorningCommandHandlerTest {

    @Mock
    private UserService userService;

    @InjectMocks
    private MorningCommandHandler handler;

    private static User user() {
        return User.create(42L, "bob", ZoneId.of("Europe/Kyiv"), "uk");
    }

    @Test
    void morning_showsCurrentHour_whenNoArgument() {
        // Arrange — default digest hour from User.create is 8
        final Update update = TelegramUpdateFixtures.command("/morning", 42L, "bob", "uk");
        when(userService.findOrCreate(update)).thenReturn(user());

        // Act
        final String reply = handler.morning(update);

        // Assert
        assertThat(reply).contains("08:00");
        verify(userService, never()).updateDigestHour(any(), anyInt());
    }

    @Test
    void morning_showsOff_whenDigestDisabled() {
        // Arrange
        final Update update = TelegramUpdateFixtures.command("/morning", 42L, "bob", "uk");
        final User user = user();
        user.setDigestHour(null);
        when(userService.findOrCreate(update)).thenReturn(user);

        // Act
        final String reply = handler.morning(update);

        // Assert
        assertThat(reply).contains("off");
    }

    @Test
    void morning_setsHour_whenWholeHourWithMinutes() {
        // Arrange
        final Update update = TelegramUpdateFixtures.command("/morning 9:00", 42L, "bob", "uk");
        final User user = user();
        when(userService.findOrCreate(update)).thenReturn(user);

        // Act
        final String reply = handler.morning(update);

        // Assert
        verify(userService).updateDigestHour(user, 9);
        assertThat(reply).contains("09:00");
    }

    @Test
    void morning_setsHour_whenBareHour() {
        // Arrange
        final Update update = TelegramUpdateFixtures.command("/morning 7", 42L, "bob", "uk");
        final User user = user();
        when(userService.findOrCreate(update)).thenReturn(user);

        // Act
        final String reply = handler.morning(update);

        // Assert
        verify(userService).updateDigestHour(user, 7);
        assertThat(reply).contains("07:00");
    }

    @Test
    void morning_disablesDigest_whenOff() {
        // Arrange
        final Update update = TelegramUpdateFixtures.command("/morning off", 42L, "bob", "uk");
        final User user = user();
        when(userService.findOrCreate(update)).thenReturn(user);

        // Act
        final String reply = handler.morning(update);

        // Assert
        verify(userService).disableDigest(user);
        assertThat(reply).contains("off");
    }

    @Test
    void morning_rejectsNonZeroMinutes_withoutPersisting() {
        // Arrange
        final Update update = TelegramUpdateFixtures.command("/morning 8:30", 42L, "bob", "uk");
        when(userService.findOrCreate(update)).thenReturn(user());

        // Act
        final String reply = handler.morning(update);

        // Assert
        assertThat(reply).contains("on the hour");
        verify(userService, never()).updateDigestHour(any(), anyInt());
    }

    @Test
    void morning_rejectsHourOutOfRange_withoutPersisting() {
        // Arrange
        final Update update = TelegramUpdateFixtures.command("/morning 24", 42L, "bob", "uk");
        when(userService.findOrCreate(update)).thenReturn(user());

        // Act
        final String reply = handler.morning(update);

        // Assert
        assertThat(reply).contains("between 0 and 23");
        verify(userService, never()).updateDigestHour(any(), anyInt());
    }

    @Test
    void morning_rejectsGarbage_withoutPersisting() {
        // Arrange
        final Update update = TelegramUpdateFixtures.command("/morning soon", 42L, "bob", "uk");
        when(userService.findOrCreate(update)).thenReturn(user());

        // Act
        final String reply = handler.morning(update);

        // Assert
        assertThat(reply).contains("whole hour");
        verify(userService, never()).updateDigestHour(any(), anyInt());
    }
}
