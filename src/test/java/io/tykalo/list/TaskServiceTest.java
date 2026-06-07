package io.tykalo.list;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.tykalo.user.User;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TaskServiceTest {

    @Mock
    private TaskRepository taskRepository;

    @Mock
    private ListRepository listRepository;

    @InjectMocks
    private TaskService taskService;

    private TaskList persistedList() {
        final User owner = User.create(1L, "owner", ZoneId.of("Europe/Kyiv"), "uk");
        owner.setId(UUID.randomUUID());
        final TaskList list = TaskList.checklist(owner, "Groceries");
        list.setId(UUID.randomUUID());
        return list;
    }

    private Task todo(final UUID id) {
        final Task task = Task.create(persistedList(), "do something");
        task.setId(id);
        return task;
    }

    @Test
    void createTask_savesTitleOnlyTask_andStripsTitle() {
        // Arrange
        final TaskList list = persistedList();
        when(listRepository.findById(list.getId())).thenReturn(Optional.of(list));
        when(taskRepository.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));

        // Act
        final Task result = taskService.createTask(list.getId(), "  Buy milk  ");

        // Assert
        final ArgumentCaptor<Task> saved = ArgumentCaptor.forClass(Task.class);
        verify(taskRepository).save(saved.capture());
        assertThat(saved.getValue().getTitle()).isEqualTo("Buy milk");
        assertThat(saved.getValue().getListId()).isEqualTo(list.getId());
        assertThat(saved.getValue().getOwnerId()).isEqualTo(list.getOwnerId());
        assertThat(saved.getValue().getStatus()).isEqualTo(TaskStatus.TODO);
        assertThat(result).isSameAs(saved.getValue());
    }

    @Test
    void createTask_storesDueAt_whenProvided() {
        // Arrange
        final TaskList list = persistedList();
        when(listRepository.findById(list.getId())).thenReturn(Optional.of(list));
        when(taskRepository.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));
        final Instant due = Instant.parse("2026-06-15T11:00:00Z");

        // Act
        final Task result = taskService.createTask(list.getId(), "Submit report", due);

        // Assert
        assertThat(result.getDueAt()).contains(due);
        assertThat(result.getTitle()).isEqualTo("Submit report");
    }

    @Test
    void createTask_rejectsBlankTitle() {
        final UUID listId = UUID.randomUUID();
        assertThatThrownBy(() -> taskService.createTask(listId, "  "))
                .isInstanceOf(IllegalArgumentException.class);
        verify(taskRepository, never()).save(any());
    }

    @Test
    void createTask_throws_whenListMissing() {
        final UUID listId = UUID.randomUUID();
        when(listRepository.findById(listId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> taskService.createTask(listId, "Buy milk"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createTasks_savesOnePerNonBlankTitle_andStrips() {
        // Arrange
        final TaskList list = persistedList();
        when(listRepository.findById(list.getId())).thenReturn(Optional.of(list));
        when(taskRepository.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));

        // Act
        final List<Task> created = taskService.createTasks(list.getId(), List.of("  milk ", "bread", "eggs"));

        // Assert
        assertThat(created).extracting(Task::getTitle).containsExactly("milk", "bread", "eggs");
        assertThat(created).allMatch(task -> task.getListId().equals(list.getId()));
    }

    @Test
    void createTasks_skipsBlankTitles() {
        final TaskList list = persistedList();
        when(listRepository.findById(list.getId())).thenReturn(Optional.of(list));
        when(taskRepository.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));

        final List<Task> created = taskService.createTasks(list.getId(), List.of("milk", "   ", "bread"));

        assertThat(created).extracting(Task::getTitle).containsExactly("milk", "bread");
        verify(taskRepository, times(2)).save(any(Task.class));
    }

    @Test
    void createTasks_throws_whenListMissing() {
        final UUID listId = UUID.randomUUID();
        when(listRepository.findById(listId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> taskService.createTasks(listId, List.of("milk")))
                .isInstanceOf(IllegalArgumentException.class);
        verify(taskRepository, never()).save(any());
    }

    @Test
    void completeTask_marksTaskDone() {
        // Arrange
        final UUID id = UUID.randomUUID();
        final Task task = todo(id);
        when(taskRepository.findById(id)).thenReturn(Optional.of(task));

        // Act
        final Task result = taskService.completeTask(id);

        // Assert
        assertThat(result.getStatus()).isEqualTo(TaskStatus.DONE);
    }

    @Test
    void completeTask_rejectsDoneToDoneTwice() {
        // Arrange
        final UUID id = UUID.randomUUID();
        final Task task = todo(id);
        task.setStatus(TaskStatus.DONE);
        when(taskRepository.findById(id)).thenReturn(Optional.of(task));

        // Act + Assert
        assertThatThrownBy(() -> taskService.completeTask(id))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void completeTask_throws_whenTaskMissing() {
        final UUID id = UUID.randomUUID();
        when(taskRepository.findById(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> taskService.completeTask(id))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void markDone_marksTodoTaskDone_andReportsChanged() {
        final UUID id = UUID.randomUUID();
        final Task task = todo(id);
        when(taskRepository.findById(id)).thenReturn(Optional.of(task));

        final TaskService.TaskToggle result = taskService.markDone(id);

        assertThat(result.changed()).isTrue();
        assertThat(result.task().getStatus()).isEqualTo(TaskStatus.DONE);
    }

    @Test
    void markDone_isIdempotent_whenTaskAlreadyDone() {
        final UUID id = UUID.randomUUID();
        final Task task = todo(id);
        task.setStatus(TaskStatus.DONE);
        when(taskRepository.findById(id)).thenReturn(Optional.of(task));

        final TaskService.TaskToggle result = taskService.markDone(id);

        assertThat(result.changed()).isFalse();
        assertThat(result.task().getStatus()).isEqualTo(TaskStatus.DONE);
    }

    @Test
    void reopen_revertsDoneTaskToTodo_andReportsChanged() {
        final UUID id = UUID.randomUUID();
        final Task task = todo(id);
        task.setStatus(TaskStatus.DONE);
        when(taskRepository.findById(id)).thenReturn(Optional.of(task));

        final TaskService.TaskToggle result = taskService.reopen(id);

        assertThat(result.changed()).isTrue();
        assertThat(result.task().getStatus()).isEqualTo(TaskStatus.TODO);
    }

    @Test
    void reopen_isIdempotent_whenTaskAlreadyTodo() {
        final UUID id = UUID.randomUUID();
        final Task task = todo(id);
        when(taskRepository.findById(id)).thenReturn(Optional.of(task));

        final TaskService.TaskToggle result = taskService.reopen(id);

        assertThat(result.changed()).isFalse();
        assertThat(result.task().getStatus()).isEqualTo(TaskStatus.TODO);
    }

    @Test
    void snoozeTask_pushesDueDateRelativeToNow() {
        // Arrange
        final UUID id = UUID.randomUUID();
        final Task task = todo(id);
        when(taskRepository.findById(id)).thenReturn(Optional.of(task));
        final Instant before = Instant.now();

        // Act
        final Task result = taskService.snoozeTask(id, Duration.ofHours(2));

        // Assert
        final Instant due = result.getDueAt().orElseThrow();
        assertThat(due).isAfter(before.plus(2, ChronoUnit.HOURS).minusSeconds(5));
        assertThat(due).isBefore(Instant.now().plus(2, ChronoUnit.HOURS).plusSeconds(5));
    }

    @Test
    void snoozeTask_rejectsNonPositiveDuration() {
        final UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> taskService.snoozeTask(id, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        verify(taskRepository, never()).findById(any());
    }

    @Test
    void snoozeTask_rejectsNonTodoTask() {
        // Arrange
        final UUID id = UUID.randomUUID();
        final Task task = todo(id);
        task.setStatus(TaskStatus.DONE);
        when(taskRepository.findById(id)).thenReturn(Optional.of(task));

        // Act + Assert
        assertThatThrownBy(() -> taskService.snoozeTask(id, Duration.ofHours(1)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void deleteTask_softDeletes_bySettingArchivedAt() {
        // Arrange
        final UUID id = UUID.randomUUID();
        final Task task = todo(id);
        when(taskRepository.findById(id)).thenReturn(Optional.of(task));

        // Act
        taskService.deleteTask(id);

        // Assert
        assertThat(task.getArchivedAt()).isNotNull();
    }

    @Test
    void findToday_queriesTheOwnersLocalCalendarDayWindow() {
        // Arrange
        final UUID ownerId = UUID.randomUUID();
        final ZoneId zone = ZoneId.of("Europe/Kyiv");
        when(taskRepository.findDueBetween(eq(ownerId), any(), any())).thenReturn(List.of());

        // Act
        taskService.findToday(ownerId, zone);

        // Assert
        final ArgumentCaptor<Instant> start = ArgumentCaptor.forClass(Instant.class);
        final ArgumentCaptor<Instant> end = ArgumentCaptor.forClass(Instant.class);
        verify(taskRepository).findDueBetween(eq(ownerId), start.capture(), end.capture());
        final LocalDate today = LocalDate.now(zone);
        assertThat(start.getValue()).isEqualTo(today.atStartOfDay(zone).toInstant());
        assertThat(end.getValue()).isEqualTo(today.plusDays(1).atStartOfDay(zone).toInstant());
    }

    @Test
    void findOverdue_delegatesToOwnerScopedQuery() {
        // Arrange
        final UUID ownerId = UUID.randomUUID();
        final Task overdue = todo(UUID.randomUUID());
        when(taskRepository.findOverdueByOwner(eq(ownerId), any())).thenReturn(List.of(overdue));

        // Act
        final List<Task> result = taskService.findOverdue(ownerId);

        // Assert
        assertThat(result).containsExactly(overdue);
    }
}
