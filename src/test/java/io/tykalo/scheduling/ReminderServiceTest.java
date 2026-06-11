package io.tykalo.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.tykalo.list.ListRenderer;
import io.tykalo.list.Task;
import io.tykalo.list.TaskList;
import io.tykalo.list.TaskRepository;
import io.tykalo.telegram.TelegramMessageGateway;
import io.tykalo.user.QuietHoursService;
import io.tykalo.user.User;
import io.tykalo.user.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

@ExtendWith(MockitoExtension.class)
class ReminderServiceTest {

    private static final Instant DUE = Instant.parse("2026-06-08T00:00:00Z");

    @Mock
    private TaskRepository taskRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ReminderLogRepository reminderLogRepository;

    @Mock
    private QuietHoursService quietHoursService;

    @Mock
    private ReminderRenderer renderer;

    @Mock
    private ListRenderer listRenderer;

    @Mock
    private TelegramMessageGateway gateway;

    @InjectMocks
    private ReminderService service;

    private static User user(final long chatId) {
        final User user = User.create(chatId, "u" + chatId, ZoneOffset.UTC, "en");
        user.setId(UUID.randomUUID());
        return user;
    }

    private static Task task(final User owner) {
        final TaskList list = TaskList.project(owner, "Launch");
        list.setId(UUID.randomUUID());
        final Task task = Task.create(list, "Ship it");
        task.setId(UUID.randomUUID());
        task.setDueAt(DUE);
        return task;
    }

    private void stubRenderAndKeyboard() {
        when(renderer.render(any(), any())).thenReturn("body");
        when(listRenderer.keyboard(any())).thenReturn(InlineKeyboardMarkup.builder().build());
    }

    @Test
    void sendDueReminders_sendsLevelOne_whenTwoHoursOverdueAndNothingSent() {
        // Arrange — 2h past due, no prior reminder
        final User owner = user(950_001L);
        final Task task = task(owner);
        final Instant now = DUE.plus(Duration.ofHours(2));
        when(taskRepository.findOverdueProjectTasks(now)).thenReturn(List.of(task));
        when(userRepository.findAllById(any())).thenReturn(List.of(owner));
        when(reminderLogRepository.findByTaskIdIn(any())).thenReturn(List.of());
        when(quietHoursService.isQuiet(owner, now)).thenReturn(false);
        stubRenderAndKeyboard();

        // Act
        service.sendDueReminders(now);

        // Assert — sent to owner and a level-1 log persisted
        verify(gateway).sendMarkdown(eq(950_001L), eq("body"), any(InlineKeyboardMarkup.class));
        final ArgumentCaptor<ReminderLog> captor = ArgumentCaptor.forClass(ReminderLog.class);
        verify(reminderLogRepository).save(captor.capture());
        assertThat(captor.getValue().getLevel()).isEqualTo(1);
    }

    @Test
    void sendDueReminders_escalatesToLevelTwo_whenSixHoursOverdueAndLevelOneSent() {
        // Arrange — 6h past due, level 1 already logged
        final User owner = user(950_002L);
        final Task task = task(owner);
        final Instant now = DUE.plus(Duration.ofHours(6));
        when(taskRepository.findOverdueProjectTasks(now)).thenReturn(List.of(task));
        when(userRepository.findAllById(any())).thenReturn(List.of(owner));
        when(reminderLogRepository.findByTaskIdIn(any()))
                .thenReturn(List.of(ReminderLog.of(task.getId(), ReminderLevel.L1, DUE.plus(Duration.ofHours(2)))));
        when(quietHoursService.isQuiet(owner, now)).thenReturn(false);
        stubRenderAndKeyboard();

        // Act
        service.sendDueReminders(now);

        // Assert
        final ArgumentCaptor<ReminderLog> captor = ArgumentCaptor.forClass(ReminderLog.class);
        verify(reminderLogRepository).save(captor.capture());
        assertThat(captor.getValue().getLevel()).isEqualTo(2);
    }

    @Test
    void sendDueReminders_doesNotResend_whenCurrentTierAlreadySent() {
        // Arrange — 3h past due (tier L1) but L1 already logged
        final User owner = user(950_003L);
        final Task task = task(owner);
        final Instant now = DUE.plus(Duration.ofHours(3));
        when(taskRepository.findOverdueProjectTasks(now)).thenReturn(List.of(task));
        when(userRepository.findAllById(any())).thenReturn(List.of(owner));
        when(reminderLogRepository.findByTaskIdIn(any()))
                .thenReturn(List.of(ReminderLog.of(task.getId(), ReminderLevel.L1, DUE.plus(Duration.ofHours(2)))));
        when(quietHoursService.isQuiet(owner, now)).thenReturn(false);

        // Act
        service.sendDueReminders(now);

        // Assert
        verify(gateway, never()).sendMarkdown(anyLong(), any(), any());
        verify(reminderLogRepository, never()).save(any());
    }

    @Test
    void sendDueReminders_skips_whenWithinQuietHours() {
        // Arrange
        final User owner = user(950_004L);
        final Task task = task(owner);
        final Instant now = DUE.plus(Duration.ofHours(2));
        when(taskRepository.findOverdueProjectTasks(now)).thenReturn(List.of(task));
        when(userRepository.findAllById(any())).thenReturn(List.of(owner));
        when(reminderLogRepository.findByTaskIdIn(any())).thenReturn(List.of());
        when(quietHoursService.isQuiet(owner, now)).thenReturn(true);

        // Act
        service.sendDueReminders(now);

        // Assert — postponed: no send, no log this tick
        verify(gateway, never()).sendMarkdown(anyLong(), any(), any());
        verify(reminderLogRepository, never()).save(any());
    }

    @Test
    void sendDueReminders_doesNothing_whenNoOverdueTasks() {
        // Arrange
        final Instant now = DUE.plus(Duration.ofHours(2));
        when(taskRepository.findOverdueProjectTasks(now)).thenReturn(List.of());

        // Act
        service.sendDueReminders(now);

        // Assert — never even resolves owners
        verify(userRepository, never()).findAllById(any());
        verify(gateway, never()).sendMarkdown(anyLong(), any(), any());
    }

    @Test
    void sendDueReminders_isolatesFailure_andContinuesToOtherTasks() {
        // Arrange — two overdue tasks of two owners; sending the first throws
        final User failing = user(950_005L);
        final User ok = user(950_006L);
        final Task failingTask = task(failing);
        final Task okTask = task(ok);
        final Instant now = DUE.plus(Duration.ofHours(2));
        when(taskRepository.findOverdueProjectTasks(now)).thenReturn(List.of(failingTask, okTask));
        when(userRepository.findAllById(any())).thenReturn(List.of(failing, ok));
        when(reminderLogRepository.findByTaskIdIn(any())).thenReturn(List.of());
        lenient().when(quietHoursService.isQuiet(any(), eq(now))).thenReturn(false);
        stubRenderAndKeyboard();
        doThrow(new RuntimeException("Telegram down")).when(gateway).sendMarkdown(eq(950_005L), any(), any());

        // Act + Assert — the sweep swallows the failure and still delivers to the second owner
        assertThatCode(() -> service.sendDueReminders(now)).doesNotThrowAnyException();
        verify(gateway).sendMarkdown(eq(950_006L), eq("body"), any(InlineKeyboardMarkup.class));
    }
}
