package io.tykalo.nudger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.tykalo.list.Task;
import io.tykalo.list.TaskList;
import io.tykalo.list.TaskRepository;
import io.tykalo.user.User;
import io.tykalo.user.UserRepository;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TaskNudgerServiceTest {

    @Mock
    private TaskNudgerRepository taskNudgerRepository;

    @Mock
    private NudgerRepository nudgerRepository;

    @Mock
    private NudgerService nudgerService;

    @Mock
    private TaskRepository taskRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private TaskNudgerService service;

    private static User user(final long chatId, final String username) {
        final User user = User.create(chatId, username, ZoneOffset.UTC, "en");
        user.setId(UUID.randomUUID());
        return user;
    }

    private static Nudger nudger(final User owner, final User nudgerUser) {
        final Nudger nudger = Nudger.invite(owner, nudgerUser);
        nudger.setId(UUID.randomUUID());
        nudger.setStatus(NudgerStatus.ACTIVE);
        return nudger;
    }

    private Task taskFor(final UUID taskId) {
        final User owner = user(1L, "owner");
        final TaskList list = TaskList.project(owner, "Launch");
        list.setId(UUID.randomUUID());
        final Task task = Task.create(list, "Ship it");
        task.setId(taskId);
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        return task;
    }

    @Test
    void assign_replacesAssignment_andClearsPrivate_whenUsernamesResolve() {
        // Arrange — @alice resolves to one of the owner's nudgers, @ghost does not
        final User owner = user(1_000_001L, "owner");
        final User alice = user(1_000_002L, "alice");
        final Nudger pair = nudger(owner, alice);
        final UUID taskId = UUID.randomUUID();
        final Task task = taskFor(taskId);
        task.setNudgersPrivate(true);
        when(nudgerService.find(owner, "alice")).thenReturn(new NudgerActionResult.Ok(pair, alice));
        when(nudgerService.find(owner, "ghost")).thenReturn(new NudgerActionResult.NotANudger("ghost"));

        // Act
        final TaskNudgerService.AssignResult result = service.assign(owner, taskId, List.of("alice", "ghost"));

        // Assert
        assertThat(result.applied()).isTrue();
        assertThat(result.assigned()).containsExactly("alice");
        assertThat(result.notNudgers()).containsExactly("ghost");
        verify(taskNudgerRepository).deleteByTaskId(taskId);
        verify(taskNudgerRepository).save(any(TaskNudger.class));
        assertThat(task.isNudgersPrivate()).isFalse();
    }

    @Test
    void assign_leavesAssignmentUntouched_whenNothingResolves() {
        // Arrange — both handles are unknown
        final User owner = user(1_000_010L, "owner");
        final UUID taskId = UUID.randomUUID();
        when(nudgerService.find(owner, "ghost")).thenReturn(new NudgerActionResult.NotRegistered("ghost"));

        // Act
        final TaskNudgerService.AssignResult result = service.assign(owner, taskId, List.of("ghost"));

        // Assert — no wipe, no save, flagged not-applied
        assertThat(result.applied()).isFalse();
        assertThat(result.notRegistered()).containsExactly("ghost");
        verify(taskNudgerRepository, never()).deleteByTaskId(any());
        verify(taskNudgerRepository, never()).save(any());
    }

    @Test
    void makePrivate_clearsAssignment_andSetsFlag() {
        // Arrange
        final UUID taskId = UUID.randomUUID();
        final Task task = taskFor(taskId);

        // Act
        service.makePrivate(taskId);

        // Assert
        verify(taskNudgerRepository).deleteByTaskId(taskId);
        assertThat(task.isNudgersPrivate()).isTrue();
    }

    @Test
    void useDefault_clearsAssignment_andClearsFlag() {
        // Arrange
        final UUID taskId = UUID.randomUUID();
        final Task task = taskFor(taskId);
        task.setNudgersPrivate(true);

        // Act
        service.useDefault(taskId);

        // Assert
        verify(taskNudgerRepository).deleteByTaskId(taskId);
        assertThat(task.isNudgersPrivate()).isFalse();
    }

    @Test
    void assignNudger_isIdempotent_whenLinkAlreadyExists() {
        // Arrange — the pair is already linked
        final UUID taskId = UUID.randomUUID();
        final UUID nudgerId = UUID.randomUUID();
        taskFor(taskId);
        when(taskNudgerRepository.existsByTaskIdAndNudgerId(taskId, nudgerId)).thenReturn(true);

        // Act
        service.assignNudger(taskId, nudgerId);

        // Assert — no second row
        verify(taskNudgerRepository, never()).save(any());
    }

    @Test
    void assignNudger_addsLink_whenAbsent() {
        // Arrange
        final UUID taskId = UUID.randomUUID();
        final UUID nudgerId = UUID.randomUUID();
        final Task task = taskFor(taskId);
        task.setNudgersPrivate(true);
        when(taskNudgerRepository.existsByTaskIdAndNudgerId(taskId, nudgerId)).thenReturn(false);

        // Act
        service.assignNudger(taskId, nudgerId);

        // Assert
        verify(taskNudgerRepository).save(any(TaskNudger.class));
        assertThat(task.isNudgersPrivate()).isFalse();
    }

    @Test
    void assignmentsByTask_groupsNudgerIdsByTask() {
        // Arrange — two tasks, three links
        final UUID taskA = UUID.randomUUID();
        final UUID taskB = UUID.randomUUID();
        final UUID nudger1 = UUID.randomUUID();
        final UUID nudger2 = UUID.randomUUID();
        final UUID nudger3 = UUID.randomUUID();
        when(taskNudgerRepository.findByTaskIdIn(any())).thenReturn(List.of(
                TaskNudger.of(taskA, nudger1), TaskNudger.of(taskA, nudger2), TaskNudger.of(taskB, nudger3)));

        // Act
        final Map<UUID, Set<UUID>> byTask = service.assignmentsByTask(List.of(taskA, taskB));

        // Assert
        assertThat(byTask.get(taskA)).containsExactlyInAnyOrder(nudger1, nudger2);
        assertThat(byTask.get(taskB)).containsExactly(nudger3);
    }

    @Test
    void assignmentsByTask_isEmpty_forNoTasks() {
        assertThat(service.assignmentsByTask(List.of())).isEmpty();
        verify(taskNudgerRepository, never()).findByTaskIdIn(any());
    }

    @Test
    void assignedUsernames_resolvesPinnedNudgersToHandles() {
        // Arrange
        final UUID taskId = UUID.randomUUID();
        final User owner = user(1L, "owner");
        final User alice = user(2L, "alice");
        final Nudger pair = nudger(owner, alice);
        when(taskNudgerRepository.findByTaskId(taskId)).thenReturn(List.of(TaskNudger.of(taskId, pair.getId())));
        when(nudgerRepository.findAllById(eq(List.of(pair.getId())))).thenReturn(List.of(pair));
        when(userRepository.findAllById(eq(List.of(alice.getId())))).thenReturn(List.of(alice));

        // Act + Assert
        assertThat(service.assignedUsernames(taskId)).containsExactly("alice");
    }
}
