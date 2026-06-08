package io.tykalo.nudger.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.tykalo.list.ListRepository;
import io.tykalo.list.Task;
import io.tykalo.list.TaskList;
import io.tykalo.list.TaskService;
import io.tykalo.nudger.TaskNudgerService;
import io.tykalo.telegram.TelegramUpdateFixtures;
import io.tykalo.user.User;
import io.tykalo.user.UserService;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.objects.Update;

@ExtendWith(MockitoExtension.class)
class TaskNudgerCommandHandlerTest {

    @Mock
    private UserService userService;

    @Mock
    private TaskService taskService;

    @Mock
    private TaskNudgerService taskNudgerService;

    @Mock
    private ListRepository listRepository;

    @InjectMocks
    private TaskNudgerCommandHandler handler;

    private final User owner = owner(1L);

    private static User owner(final long chatId) {
        final User user = User.create(chatId, "owner", ZoneOffset.UTC, "en");
        user.setId(UUID.randomUUID());
        return user;
    }

    private Task projectTask(final String title) {
        final TaskList list = TaskList.project(owner, "Launch");
        list.setId(UUID.randomUUID());
        final Task task = Task.create(list, title);
        task.setId(UUID.randomUUID());
        when(listRepository.findById(list.getId())).thenReturn(Optional.of(list));
        return task;
    }

    private void owns(final Task task) {
        when(userService.findOrCreate(any(Update.class))).thenReturn(owner);
        when(taskService.find(task.getId())).thenReturn(Optional.of(task));
    }

    private Update cmd(final String text) {
        return TelegramUpdateFixtures.command(text, 1L, "owner", "en");
    }

    @Test
    void assign_pinsResolvedNudgers() {
        final Task task = projectTask("Ship v2");
        owns(task);
        when(taskNudgerService.assign(eq(owner), eq(task.getId()), any()))
                .thenReturn(new TaskNudgerService.AssignResult(List.of("alice"), List.of(), List.of(), true));

        final String reply = handler.task(cmd("/task " + task.getId() + " nudgers @alice"));

        assertThat(reply).contains("Ship v2").contains("@alice");
        verify(taskNudgerService).assign(eq(owner), eq(task.getId()), any());
    }

    @Test
    void assign_reportsUnresolvedHandles_whenNothingApplied() {
        final Task task = projectTask("Ship v2");
        owns(task);
        when(taskNudgerService.assign(eq(owner), eq(task.getId()), any()))
                .thenReturn(new TaskNudgerService.AssignResult(List.of(), List.of("ghost"), List.of(), false));

        final String reply = handler.task(cmd("/task " + task.getId() + " nudgers @ghost"));

        assertThat(reply).contains("None").contains("ghost");
    }

    @Test
    void off_makesTaskPrivate() {
        final Task task = projectTask("Secret plan");
        owns(task);

        final String reply = handler.task(cmd("/task " + task.getId() + " nudgers off"));

        assertThat(reply).contains("private");
        verify(taskNudgerService).makePrivate(task.getId());
    }

    @Test
    void show_reportsDefault_whenNoAssignment() {
        final Task task = projectTask("Ship v2");
        owns(task);
        when(taskNudgerService.assignedUsernames(task.getId())).thenReturn(List.of());

        final String reply = handler.task(cmd("/task " + task.getId() + " nudgers"));

        assertThat(reply).contains("all your active Nudgers");
    }

    @Test
    void show_reportsPrivate_whenTaskIsPrivate() {
        final Task task = projectTask("Ship v2");
        task.setNudgersPrivate(true);
        owns(task);

        final String reply = handler.task(cmd("/task " + task.getId() + " nudgers"));

        assertThat(reply).contains("private");
        verify(taskNudgerService, never()).assignedUsernames(any());
    }

    @Test
    void rejects_nonProjectTask() {
        when(userService.findOrCreate(any(Update.class))).thenReturn(owner);
        final TaskList checklist = TaskList.checklist(owner, "Groceries");
        checklist.setId(UUID.randomUUID());
        final Task task = Task.create(checklist, "Milk");
        task.setId(UUID.randomUUID());
        when(taskService.find(task.getId())).thenReturn(Optional.of(task));
        when(listRepository.findById(checklist.getId())).thenReturn(Optional.of(checklist));

        final String reply = handler.task(cmd("/task " + task.getId() + " nudgers @alice"));

        assertThat(reply).contains("only apply to Project tasks");
        verify(taskNudgerService, never()).assign(any(), any(), any());
    }

    @Test
    void rejects_taskOwnedBySomeoneElse() {
        when(userService.findOrCreate(any(Update.class))).thenReturn(owner);
        final User other = owner(2L);
        final TaskList list = TaskList.project(other, "Theirs");
        list.setId(UUID.randomUUID());
        final Task task = Task.create(list, "Not mine");
        task.setId(UUID.randomUUID());
        when(taskService.find(task.getId())).thenReturn(Optional.of(task));

        final String reply = handler.task(cmd("/task " + task.getId() + " nudgers @alice"));

        assertThat(reply).contains("isn't yours");
    }

    @Test
    void showsUsage_whenSubcommandMissing() {
        final String reply = handler.task(cmd("/task " + UUID.randomUUID()));

        assertThat(reply).contains("Usage");
        verify(userService, never()).findOrCreate(any(Update.class));
    }
}
