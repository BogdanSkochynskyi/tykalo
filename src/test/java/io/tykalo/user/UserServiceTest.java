package io.tykalo.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.tykalo.telegram.TelegramUpdateFixtures;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.telegram.telegrambots.meta.api.objects.Update;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private TimezoneResolver timezoneResolver;

    @Mock
    private ApplicationEventPublisher events;

    @InjectMocks
    private UserService userService;

    @Test
    void findOrCreate_returnsExistingUser_withoutCreating() {
        // Arrange
        final Update update = TelegramUpdateFixtures.command("/start", 42L, "bob", "uk");
        final User existing = User.create(42L, "bob", ZoneId.of("Europe/Kyiv"), "uk");
        when(userRepository.findByTgChatId(42L)).thenReturn(Optional.of(existing));

        // Act
        final User result = userService.findOrCreate(update);

        // Assert
        assertThat(result).isSameAs(existing);
        verify(userRepository, never()).save(any());
        verify(events, never()).publishEvent(any());
    }

    @Test
    void findOrCreate_createsUser_onFirstContact() {
        // Arrange
        final Update update = TelegramUpdateFixtures.command("/start", 42L, "bob", "uk");
        when(userRepository.findByTgChatId(42L)).thenReturn(Optional.empty());
        when(timezoneResolver.resolve("uk")).thenReturn(ZoneId.of("Europe/Kyiv"));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        final User result = userService.findOrCreate(update);

        // Assert
        final ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getTgChatId()).isEqualTo(42L);
        assertThat(saved.getValue().getTgUsername()).isEqualTo("bob");
        assertThat(saved.getValue().getTimezone()).isEqualTo(ZoneId.of("Europe/Kyiv"));
        assertThat(saved.getValue().getLocale()).isEqualTo("uk");
        assertThat(saved.getValue().getQuietHoursStart()).isEqualTo(LocalTime.of(22, 0));
        assertThat(saved.getValue().getQuietHoursEnd()).isEqualTo(LocalTime.of(7, 0));
        assertThat(result).isSameAs(saved.getValue());
    }

    @Test
    void findOrCreate_publishesUserCreatedEvent_onFirstContact() {
        // Arrange
        final Update update = TelegramUpdateFixtures.command("/start", 42L, "bob", "uk");
        when(userRepository.findByTgChatId(42L)).thenReturn(Optional.empty());
        when(timezoneResolver.resolve("uk")).thenReturn(ZoneId.of("Europe/Kyiv"));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        final User result = userService.findOrCreate(update);

        // Assert
        final ArgumentCaptor<UserCreatedEvent> event = ArgumentCaptor.forClass(UserCreatedEvent.class);
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue().user()).isSameAs(result);
    }

    @Test
    void findOrCreate_rejectsUpdate_withoutMessage() {
        assertThatThrownBy(() -> userService.findOrCreate(TelegramUpdateFixtures.withoutMessage()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
