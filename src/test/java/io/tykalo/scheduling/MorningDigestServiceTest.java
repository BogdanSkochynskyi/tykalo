package io.tykalo.scheduling;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.tykalo.list.ListRenderer;
import io.tykalo.list.Task;
import io.tykalo.list.TaskList;
import io.tykalo.list.TaskService;
import io.tykalo.telegram.TelegramMessageGateway;
import io.tykalo.user.QuietHoursService;
import io.tykalo.user.User;
import io.tykalo.user.UserRepository;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

@ExtendWith(MockitoExtension.class)
class MorningDigestServiceTest {

    /** 2026-06-08T06:00:00Z — 06:00 in UTC, 08:00 in a fixed +02:00 zone. */
    private static final Instant SIX_UTC = Instant.parse("2026-06-08T06:00:00Z");
    private static final ZoneId PLUS_TWO = ZoneOffset.ofHours(2);

    @Mock
    private UserRepository userRepository;

    @Mock
    private TaskService taskService;

    @Mock
    private QuietHoursService quietHoursService;

    @Mock
    private MorningDigestRenderer renderer;

    @Mock
    private ListRenderer listRenderer;

    @Mock
    private TelegramMessageGateway gateway;

    @InjectMocks
    private MorningDigestService service;

    private static User user(final long chatId, final ZoneId zone, final int digestHour) {
        final User user = User.create(chatId, "u" + chatId, zone, "en");
        user.setId(UUID.randomUUID());
        user.setQuietHoursStart(null);
        user.setQuietHoursEnd(null);
        user.setDigestHour(digestHour);
        return user;
    }

    private static Task task(final UUID ownerId) {
        final User owner = new User();
        owner.setId(ownerId);
        final TaskList list = TaskList.project(owner, "Launch");
        list.setId(UUID.randomUUID());
        final Task task = Task.create(list, "Ship it");
        task.setId(UUID.randomUUID());
        task.setDueAt(SIX_UTC);
        return task;
    }

    @Test
    void sendDigests_sendsDigest_whenLocalHourMatchesAndTasksExist() {
        // Arrange — user in +02:00 with digest at 08:00; at 06:00Z it is 08:00 local
        final User user = user(42L, PLUS_TWO, 8);
        final List<Task> tasks = List.of(task(user.getId()));
        when(userRepository.findByDigestHourIsNotNull()).thenReturn(List.of(user));
        when(quietHoursService.isQuiet(user, SIX_UTC)).thenReturn(false);
        when(taskService.findProjectTasksDueToday(user.getId(), PLUS_TWO)).thenReturn(tasks);
        when(renderer.render(tasks, PLUS_TWO)).thenReturn("body");
        when(listRenderer.keyboard(tasks)).thenReturn(InlineKeyboardMarkup.builder().build());

        // Act
        service.sendDigests(SIX_UTC);

        // Assert
        verify(gateway).sendMarkdown(eq(42L), eq("body"), any(InlineKeyboardMarkup.class));
    }

    @Test
    void sendDigests_skips_whenNotTheDigestHour() {
        // Arrange — digest at 09:00 local, but 06:00Z is 08:00 local
        final User user = user(42L, PLUS_TWO, 9);
        when(userRepository.findByDigestHourIsNotNull()).thenReturn(List.of(user));

        // Act
        service.sendDigests(SIX_UTC);

        // Assert
        verify(taskService, never()).findProjectTasksDueToday(any(), any());
        verify(gateway, never()).sendMarkdown(anyLong(), any(), any());
    }

    @Test
    void sendDigests_skips_whenWithinQuietHours() {
        // Arrange
        final User user = user(42L, PLUS_TWO, 8);
        when(userRepository.findByDigestHourIsNotNull()).thenReturn(List.of(user));
        when(quietHoursService.isQuiet(user, SIX_UTC)).thenReturn(true);

        // Act
        service.sendDigests(SIX_UTC);

        // Assert
        verify(taskService, never()).findProjectTasksDueToday(any(), any());
        verify(gateway, never()).sendMarkdown(anyLong(), any(), any());
    }

    @Test
    void sendDigests_skips_whenNoProjectTasksDueToday() {
        // Arrange
        final User user = user(42L, PLUS_TWO, 8);
        when(userRepository.findByDigestHourIsNotNull()).thenReturn(List.of(user));
        when(quietHoursService.isQuiet(user, SIX_UTC)).thenReturn(false);
        when(taskService.findProjectTasksDueToday(user.getId(), PLUS_TWO)).thenReturn(List.of());

        // Act
        service.sendDigests(SIX_UTC);

        // Assert
        verify(gateway, never()).sendMarkdown(anyLong(), any(), any());
    }

    @Test
    void sendDigests_fallsBackToUtc_whenTimezoneUnset() {
        // Arrange — no zone, digest at 06:00; 06:00Z is 06:00 in UTC
        final User user = user(42L, ZoneOffset.UTC, 6);
        user.setTimezone(null);
        final List<Task> tasks = List.of(task(user.getId()));
        when(userRepository.findByDigestHourIsNotNull()).thenReturn(List.of(user));
        when(quietHoursService.isQuiet(user, SIX_UTC)).thenReturn(false);
        when(taskService.findProjectTasksDueToday(user.getId(), ZoneOffset.UTC)).thenReturn(tasks);
        when(renderer.render(tasks, ZoneOffset.UTC)).thenReturn("body");
        when(listRenderer.keyboard(tasks)).thenReturn(InlineKeyboardMarkup.builder().build());

        // Act
        service.sendDigests(SIX_UTC);

        // Assert
        verify(gateway).sendMarkdown(eq(42L), eq("body"), any(InlineKeyboardMarkup.class));
    }

    @Test
    void sendDigests_isolatesFailure_andContinuesToOtherUsers() {
        // Arrange — two due users; sending to the first throws
        final User failing = user(42L, PLUS_TWO, 8);
        final User ok = user(43L, PLUS_TWO, 8);
        final List<Task> failingTasks = List.of(task(failing.getId()));
        final List<Task> okTasks = List.of(task(ok.getId()));
        when(userRepository.findByDigestHourIsNotNull()).thenReturn(List.of(failing, ok));
        lenient().when(quietHoursService.isQuiet(any(), eq(SIX_UTC))).thenReturn(false);
        when(taskService.findProjectTasksDueToday(failing.getId(), PLUS_TWO)).thenReturn(failingTasks);
        when(taskService.findProjectTasksDueToday(ok.getId(), PLUS_TWO)).thenReturn(okTasks);
        when(renderer.render(any(), eq(PLUS_TWO))).thenReturn("body");
        when(listRenderer.keyboard(any())).thenReturn(InlineKeyboardMarkup.builder().build());
        when(gateway.sendMarkdown(eq(42L), any(), any())).thenThrow(new RuntimeException("Telegram down"));

        // Act + Assert — the sweep swallows the failure and still delivers to the second user
        assertThatCode(() -> service.sendDigests(SIX_UTC)).doesNotThrowAnyException();
        verify(gateway).sendMarkdown(eq(43L), eq("body"), any(InlineKeyboardMarkup.class));
    }
}
