package io.tykalo.list.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.tykalo.list.ListService;
import io.tykalo.list.Task;
import io.tykalo.list.TaskList;
import io.tykalo.list.TaskService;
import io.tykalo.list.TaskViewRenderer;
import io.tykalo.telegram.TelegramUpdateFixtures;
import io.tykalo.user.User;
import io.tykalo.user.UserService;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.objects.Update;

@ExtendWith(MockitoExtension.class)
class TaskViewCommandHandlerTest {

    private static final ZoneId KYIV = ZoneId.of("Europe/Kyiv");

    @Mock
    private UserService userService;

    @Mock
    private TaskService taskService;

    @Mock
    private ListService listService;

    @Mock
    private TaskViewRenderer renderer;

    @InjectMocks
    private TaskViewCommandHandler handler;

    private final User owner = owner(1L);

    private static User owner(final long chatId) {
        final User user = User.create(chatId, "owner", KYIV, "uk");
        user.setId(UUID.randomUUID());
        return user;
    }

    private Task task(final UUID ownerId, final String title) {
        final User listOwner = User.create(99L, "list-owner", KYIV, "uk");
        listOwner.setId(ownerId);
        final TaskList list = TaskList.checklist(listOwner, "Work");
        list.setId(UUID.randomUUID());
        final Task task = Task.create(list, title);
        task.setId(UUID.randomUUID());
        return task;
    }

    private Update cmd(final String text) {
        return TelegramUpdateFixtures.command(text, 1L, "owner", "uk");
    }

    @Test
    void today_resolvesTasks_namesLists_andRenders() {
        // Arrange
        when(userService.findOrCreate(any(Update.class))).thenReturn(owner);
        final Task task = task(owner.getId(), "Submit report");
        when(taskService.findToday(owner.getId(), KYIV)).thenReturn(List.of(task));
        final Map<UUID, String> names = Map.of(task.getListId(), "Work");
        when(listService.namesByIds(anyList())).thenReturn(names);
        when(renderer.today(List.of(task), names, KYIV)).thenReturn("RENDERED_TODAY");

        // Act
        final String reply = handler.today(cmd("/today"));

        // Assert
        assertThat(reply).isEqualTo("RENDERED_TODAY");
        verify(taskService).findToday(owner.getId(), KYIV);
    }

    @Test
    void overdue_delegatesToOverdueQuery_andRenders() {
        // Arrange
        when(userService.findOrCreate(any(Update.class))).thenReturn(owner);
        final Task task = task(owner.getId(), "Old task");
        when(taskService.findOverdue(owner.getId())).thenReturn(List.of(task));
        when(listService.namesByIds(anyList())).thenReturn(Map.of(task.getListId(), "Work"));
        when(renderer.overdue(eq(List.of(task)), any(), eq(KYIV))).thenReturn("RENDERED_OVERDUE");

        // Act
        final String reply = handler.overdue(cmd("/overdue"));

        // Assert
        assertThat(reply).isEqualTo("RENDERED_OVERDUE");
        verify(taskService).findOverdue(owner.getId());
    }

    @Test
    void week_delegatesToWeekQuery_andRenders() {
        // Arrange
        when(userService.findOrCreate(any(Update.class))).thenReturn(owner);
        final Task task = task(owner.getId(), "Plan");
        when(taskService.findWeek(owner.getId(), KYIV)).thenReturn(List.of(task));
        when(renderer.week(List.of(task), KYIV)).thenReturn("RENDERED_WEEK");

        // Act
        final String reply = handler.week(cmd("/week"));

        // Assert
        assertThat(reply).isEqualTo("RENDERED_WEEK");
        verify(taskService).findWeek(owner.getId(), KYIV);
    }

    @Test
    void done_marksOwnTaskDone() {
        // Arrange
        when(userService.findOrCreate(any(Update.class))).thenReturn(owner);
        final Task task = task(owner.getId(), "Buy milk");
        when(taskService.find(task.getId())).thenReturn(Optional.of(task));
        when(taskService.markDone(task.getId())).thenReturn(new TaskService.TaskToggle(task, true));

        // Act
        final String reply = handler.done(cmd("/done " + task.getId()));

        // Assert
        assertThat(reply).contains("Done").contains("Buy milk");
        verify(taskService).markDone(task.getId());
    }

    @Test
    void done_reportsAlreadyDone_whenIdempotentNoOp() {
        // Arrange
        when(userService.findOrCreate(any(Update.class))).thenReturn(owner);
        final Task task = task(owner.getId(), "Buy milk");
        when(taskService.find(task.getId())).thenReturn(Optional.of(task));
        when(taskService.markDone(task.getId())).thenReturn(new TaskService.TaskToggle(task, false));

        // Act
        final String reply = handler.done(cmd("/done " + task.getId()));

        // Assert
        assertThat(reply).contains("already done");
    }

    @Test
    void done_rejectsBlankArg_withUsage() {
        final String reply = handler.done(cmd("/done"));

        assertThat(reply).contains("Usage");
        verify(taskService, never()).markDone(any());
    }

    @Test
    void done_rejectsMalformedId() {
        final String reply = handler.done(cmd("/done not-a-uuid"));

        assertThat(reply).contains("not a valid task id");
        verify(taskService, never()).markDone(any());
    }

    @Test
    void done_reportsNotFound_whenNoSuchTask() {
        // Arrange
        when(userService.findOrCreate(any(Update.class))).thenReturn(owner);
        final UUID id = UUID.randomUUID();
        when(taskService.find(id)).thenReturn(Optional.empty());

        // Act
        final String reply = handler.done(cmd("/done " + id));

        // Assert
        assertThat(reply).contains("not found");
        verify(taskService, never()).markDone(any());
    }

    @Test
    void done_refusesTaskOwnedBySomeoneElse() {
        // Arrange
        when(userService.findOrCreate(any(Update.class))).thenReturn(owner);
        final Task someoneElses = task(UUID.randomUUID(), "Their task");
        when(taskService.find(someoneElses.getId())).thenReturn(Optional.of(someoneElses));

        // Act
        final String reply = handler.done(cmd("/done " + someoneElses.getId()));

        // Assert
        assertThat(reply).contains("isn't yours");
        verify(taskService, never()).markDone(any());
    }
}
