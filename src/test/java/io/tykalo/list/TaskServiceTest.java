package io.tykalo.list;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
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

    @Mock
    private io.tykalo.user.UserRepository userRepository;

    @Mock
    private RecurrenceCalculator recurrenceCalculator;

    @Mock
    private org.springframework.context.ApplicationEventPublisher eventPublisher;

    @Mock
    private ListPermissionService permissionService;

    @Mock
    private PendingItemService pendingItemService;

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

    private Task recurringDueTask(final UUID id, final String rule, final Instant dueAt) {
        final Task task = todo(id);
        task.setRecurrenceRule(rule);
        task.setDueAt(dueAt);
        task.setDescription("details");
        task.setPriority(Priority.HIGH);
        task.setTags(List.of("home"));
        return task;
    }

    @Test
    void completeTask_spawnsNextInstance_whenRecurringAndDue() {
        // Arrange
        final UUID id = UUID.randomUUID();
        final Instant due = Instant.parse("2026-06-08T06:00:00Z");
        final Instant next = Instant.parse("2026-06-09T06:00:00Z");
        final Task task = recurringDueTask(id, "FREQ=DAILY", due);
        when(taskRepository.findById(id)).thenReturn(Optional.of(task));
        when(userRepository.findById(task.getOwnerId())).thenReturn(Optional.empty());
        when(recurrenceCalculator.nextOccurrence(eq("FREQ=DAILY"), eq(due), any()))
                .thenReturn(Optional.of(next));
        when(taskRepository.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));

        // Act
        taskService.completeTask(id);

        // Assert
        final ArgumentCaptor<Task> saved = ArgumentCaptor.forClass(Task.class);
        verify(taskRepository).save(saved.capture());
        final Task created = saved.getValue();
        assertThat(created.getStatus()).isEqualTo(TaskStatus.TODO);
        assertThat(created.getDueAt()).contains(next);
        assertThat(created.getTitle()).isEqualTo(task.getTitle());
        assertThat(created.getDescription()).contains("details");
        assertThat(created.getPriority()).contains(Priority.HIGH);
        assertThat(created.getRecurrenceRule()).contains("FREQ=DAILY");
        assertThat(created.getTags()).containsExactly("home");
        assertThat(created.getListId()).isEqualTo(task.getListId());
        assertThat(created.getArchivedAt()).isNull();
    }

    @Test
    void markDone_spawnsNextInstance_whenRecurringAndDue() {
        final UUID id = UUID.randomUUID();
        final Instant due = Instant.parse("2026-06-08T06:00:00Z");
        final Instant next = Instant.parse("2026-06-15T06:00:00Z");
        final Task task = recurringDueTask(id, "FREQ=WEEKLY", due);
        when(taskRepository.findById(id)).thenReturn(Optional.of(task));
        when(userRepository.findById(task.getOwnerId())).thenReturn(Optional.empty());
        when(recurrenceCalculator.nextOccurrence(eq("FREQ=WEEKLY"), eq(due), any()))
                .thenReturn(Optional.of(next));
        when(taskRepository.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));

        taskService.markDone(id);

        final ArgumentCaptor<Task> saved = ArgumentCaptor.forClass(Task.class);
        verify(taskRepository).save(saved.capture());
        assertThat(saved.getValue().getDueAt()).contains(next);
    }

    @Test
    void markDone_doesNotSpawn_whenAlreadyDone() {
        final UUID id = UUID.randomUUID();
        final Task task = recurringDueTask(id, "FREQ=DAILY", Instant.parse("2026-06-08T06:00:00Z"));
        task.setStatus(TaskStatus.DONE);
        when(taskRepository.findById(id)).thenReturn(Optional.of(task));

        taskService.markDone(id);

        verify(taskRepository, never()).save(any());
    }

    @Test
    void completeTask_doesNotSpawn_whenNoRecurrenceRule() {
        final UUID id = UUID.randomUUID();
        final Task task = todo(id);
        task.setDueAt(Instant.parse("2026-06-08T06:00:00Z"));
        when(taskRepository.findById(id)).thenReturn(Optional.of(task));

        taskService.completeTask(id);

        verify(taskRepository, never()).save(any());
    }

    @Test
    void completeTask_doesNotSpawn_whenRecurringButNoDueDate() {
        final UUID id = UUID.randomUUID();
        final Task task = todo(id);
        task.setRecurrenceRule("FREQ=DAILY");
        when(taskRepository.findById(id)).thenReturn(Optional.of(task));

        taskService.completeTask(id);

        verify(taskRepository, never()).save(any());
    }

    @Test
    void completeTask_doesNotSpawn_whenTaskArchived() {
        final UUID id = UUID.randomUUID();
        final Task task = recurringDueTask(id, "FREQ=DAILY", Instant.parse("2026-06-08T06:00:00Z"));
        task.setArchivedAt(Instant.now());
        when(taskRepository.findById(id)).thenReturn(Optional.of(task));

        taskService.completeTask(id);

        verify(taskRepository, never()).save(any());
    }

    @Test
    void completeTask_doesNotSpawn_whenCalculatorReturnsEmpty() {
        final UUID id = UUID.randomUUID();
        final Instant due = Instant.parse("2026-06-08T06:00:00Z");
        final Task task = recurringDueTask(id, "FREQ=MONTHLY", due);
        when(taskRepository.findById(id)).thenReturn(Optional.of(task));
        when(userRepository.findById(task.getOwnerId())).thenReturn(Optional.empty());
        when(recurrenceCalculator.nextOccurrence(eq("FREQ=MONTHLY"), eq(due), any()))
                .thenReturn(Optional.empty());

        taskService.completeTask(id);

        verify(taskRepository, never()).save(any());
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
    void snoozeUntil_setsExplicitFutureDueDate() {
        final UUID id = UUID.randomUUID();
        final Task task = todo(id);
        when(taskRepository.findById(id)).thenReturn(Optional.of(task));
        final Instant until = Instant.now().plus(3, ChronoUnit.DAYS);

        final Task result = taskService.snoozeUntil(id, until);

        assertThat(result.getDueAt()).contains(until);
    }

    @Test
    void snoozeUntil_rejectsPastTarget() {
        final UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> taskService.snoozeUntil(id, Instant.now().minusSeconds(60)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(taskRepository, never()).findById(any());
    }

    @Test
    void snoozeUntil_rejectsNonTodoTask() {
        final UUID id = UUID.randomUUID();
        final Task task = todo(id);
        task.setStatus(TaskStatus.DONE);
        when(taskRepository.findById(id)).thenReturn(Optional.of(task));

        assertThatThrownBy(() -> taskService.snoozeUntil(id, Instant.now().plusSeconds(3600)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void updateTitle_stripsAndSets() {
        final UUID id = UUID.randomUUID();
        final Task task = todo(id);
        when(taskRepository.findById(id)).thenReturn(Optional.of(task));

        final Task result = taskService.updateTitle(id, "  New title  ");

        assertThat(result.getTitle()).isEqualTo("New title");
    }

    @Test
    void updateTitle_rejectsBlank() {
        final UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> taskService.updateTitle(id, "   "))
                .isInstanceOf(IllegalArgumentException.class);
        verify(taskRepository, never()).findById(any());
    }

    @Test
    void updateDescription_setsStrippedText() {
        final UUID id = UUID.randomUUID();
        final Task task = todo(id);
        when(taskRepository.findById(id)).thenReturn(Optional.of(task));

        final Task result = taskService.updateDescription(id, "  some details ");

        assertThat(result.getDescription()).contains("some details");
    }

    @Test
    void updateDescription_clearsOnBlank() {
        final UUID id = UUID.randomUUID();
        final Task task = todo(id);
        task.setDescription("old");
        when(taskRepository.findById(id)).thenReturn(Optional.of(task));

        final Task result = taskService.updateDescription(id, "   ");

        assertThat(result.getDescription()).isEmpty();
    }

    @Test
    void updateDueAt_setsDeadline() {
        final UUID id = UUID.randomUUID();
        final Task task = todo(id);
        when(taskRepository.findById(id)).thenReturn(Optional.of(task));
        final Instant due = Instant.parse("2026-07-01T08:00:00Z");

        final Task result = taskService.updateDueAt(id, due);

        assertThat(result.getDueAt()).contains(due);
    }

    @Test
    void updatePriority_setsPriority() {
        final UUID id = UUID.randomUUID();
        final Task task = todo(id);
        when(taskRepository.findById(id)).thenReturn(Optional.of(task));

        final Task result = taskService.updatePriority(id, Priority.HIGH);

        assertThat(result.getPriority()).contains(Priority.HIGH);
    }

    @Test
    void updateTitle_throws_whenTaskMissing() {
        final UUID id = UUID.randomUUID();
        when(taskRepository.findById(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> taskService.updateTitle(id, "New"))
                .isInstanceOf(IllegalArgumentException.class);
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
    void findWeek_queriesSevenDayWindowFromTodayStart() {
        // Arrange
        final UUID ownerId = UUID.randomUUID();
        final ZoneId zone = ZoneId.of("Europe/Kyiv");
        when(taskRepository.findDueBetween(eq(ownerId), any(), any())).thenReturn(List.of());

        // Act
        taskService.findWeek(ownerId, zone);

        // Assert
        final ArgumentCaptor<Instant> start = ArgumentCaptor.forClass(Instant.class);
        final ArgumentCaptor<Instant> end = ArgumentCaptor.forClass(Instant.class);
        verify(taskRepository).findDueBetween(eq(ownerId), start.capture(), end.capture());
        final LocalDate today = LocalDate.now(zone);
        assertThat(start.getValue()).isEqualTo(today.atStartOfDay(zone).toInstant());
        assertThat(end.getValue()).isEqualTo(today.plusDays(7).atStartOfDay(zone).toInstant());
    }

    @Test
    void find_returnsTaskById() {
        final UUID id = UUID.randomUUID();
        final Task task = todo(id);
        when(taskRepository.findById(id)).thenReturn(Optional.of(task));

        assertThat(taskService.find(id)).contains(task);
    }

    @Test
    void createTask_announcesListChange_soTheLiveMessageRefreshes() {
        final TaskList list = persistedList();
        when(listRepository.findById(list.getId())).thenReturn(Optional.of(list));
        when(taskRepository.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));

        taskService.createTask(list.getId(), "Buy milk");

        verify(eventPublisher).publishEvent(new ListChangedEvent(list.getId()));
    }

    @Test
    void createTasks_announcesListChangeOnce_whenAnythingWasCreated() {
        final TaskList list = persistedList();
        when(listRepository.findById(list.getId())).thenReturn(Optional.of(list));
        when(taskRepository.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));

        taskService.createTasks(list.getId(), List.of("milk", "bread"));

        verify(eventPublisher, times(1)).publishEvent(new ListChangedEvent(list.getId()));
    }

    @Test
    void createTasks_doesNotAnnounce_whenAllTitlesBlank() {
        final TaskList list = persistedList();
        when(listRepository.findById(list.getId())).thenReturn(Optional.of(list));

        taskService.createTasks(list.getId(), List.of("   ", ""));

        verify(eventPublisher, never()).publishEvent(any(ListChangedEvent.class));
    }

    @Test
    void markDone_announcesListChange_whenItToggled() {
        final UUID id = UUID.randomUUID();
        final Task task = todo(id);
        when(taskRepository.findById(id)).thenReturn(Optional.of(task));

        taskService.markDone(id);

        verify(eventPublisher).publishEvent(new ListChangedEvent(task.getListId()));
    }

    @Test
    void markDone_doesNotAnnounce_whenAlreadyDone() {
        final UUID id = UUID.randomUUID();
        final Task task = todo(id);
        task.setStatus(TaskStatus.DONE);
        when(taskRepository.findById(id)).thenReturn(Optional.of(task));

        taskService.markDone(id);

        verify(eventPublisher, never()).publishEvent(any(ListChangedEvent.class));
    }

    @Test
    void reopen_announcesListChange_whenItToggled() {
        final UUID id = UUID.randomUUID();
        final Task task = todo(id);
        task.setStatus(TaskStatus.DONE);
        when(taskRepository.findById(id)).thenReturn(Optional.of(task));

        taskService.reopen(id);

        verify(eventPublisher).publishEvent(new ListChangedEvent(task.getListId()));
    }

    @Test
    void updateTitle_announcesListChange() {
        final UUID id = UUID.randomUUID();
        final Task task = todo(id);
        when(taskRepository.findById(id)).thenReturn(Optional.of(task));

        taskService.updateTitle(id, "New title");

        verify(eventPublisher).publishEvent(new ListChangedEvent(task.getListId()));
    }

    @Test
    void snoozeUntil_announcesListChange() {
        final UUID id = UUID.randomUUID();
        final Task task = todo(id);
        when(taskRepository.findById(id)).thenReturn(Optional.of(task));

        taskService.snoozeUntil(id, Instant.now().plus(1, ChronoUnit.DAYS));

        verify(eventPublisher).publishEvent(new ListChangedEvent(task.getListId()));
    }

    @Test
    void deleteTask_announcesListChange_onFirstArchive_butNotAgain() {
        final UUID id = UUID.randomUUID();
        final Task task = todo(id);
        when(taskRepository.findById(id)).thenReturn(Optional.of(task));

        taskService.deleteTask(id);
        taskService.deleteTask(id);

        verify(eventPublisher, times(1)).publishEvent(new ListChangedEvent(task.getListId()));
    }

    @Test
    void counts_reportsTotalAndDoneForAList() {
        final UUID listId = UUID.randomUUID();
        when(taskRepository.countByListIdAndArchivedAtIsNull(listId)).thenReturn(5L);
        when(taskRepository.countByListIdAndStatusAndArchivedAtIsNull(listId, TaskStatus.DONE)).thenReturn(2L);

        final TaskService.Counts counts = taskService.counts(listId);

        assertThat(counts.total()).isEqualTo(5L);
        assertThat(counts.done()).isEqualTo(2L);
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

    @Test
    void createTask_actorAware_checksAddPermissionThenDelegates() {
        // Arrange
        final UUID actor = UUID.randomUUID();
        final TaskList list = persistedList();
        when(listRepository.findById(list.getId())).thenReturn(Optional.of(list));
        when(taskRepository.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));

        // Act
        final Task result = taskService.createTask(actor, list.getId(), "Buy milk");

        // Assert
        verify(permissionService).requireCanAddItems(actor, list.getId());
        assertThat(result.getTitle()).isEqualTo("Buy milk");
    }

    @Test
    void createTask_actorAware_throws_andDoesNotSave_whenPermissionDenied() {
        // Arrange
        final UUID actor = UUID.randomUUID();
        final UUID listId = UUID.randomUUID();
        doThrow(new ListPermissionDeniedException(actor, listId, "add items", ListMemberRole.MEMBER))
                .when(permissionService).requireCanAddItems(actor, listId);

        // Act + Assert
        assertThatThrownBy(() -> taskService.createTask(actor, listId, "Buy milk"))
                .isInstanceOf(ListPermissionDeniedException.class);
        verify(taskRepository, never()).save(any());
    }

    @Test
    void markDone_actorAware_checksTogglePermissionForTasksList_thenDelegates() {
        // Arrange
        final UUID actor = UUID.randomUUID();
        final UUID id = UUID.randomUUID();
        final Task task = todo(id);
        when(taskRepository.findById(id)).thenReturn(Optional.of(task));

        // Act
        final TaskService.TaskToggle result = taskService.markDone(actor, id);

        // Assert
        verify(permissionService).requireCanToggleItems(actor, task.getListId());
        assertThat(result.task().getStatus()).isEqualTo(TaskStatus.DONE);
    }

    @Test
    void reopen_actorAware_throws_whenTogglePermissionDenied() {
        // Arrange
        final UUID actor = UUID.randomUUID();
        final UUID id = UUID.randomUUID();
        final Task task = todo(id);
        task.setStatus(TaskStatus.DONE);
        when(taskRepository.findById(id)).thenReturn(Optional.of(task));
        doThrow(new ListPermissionDeniedException(actor, task.getListId(), "toggle items", null))
                .when(permissionService).requireCanToggleItems(actor, task.getListId());

        // Act + Assert
        assertThatThrownBy(() -> taskService.reopen(actor, id))
                .isInstanceOf(ListPermissionDeniedException.class);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.DONE);
    }

    @Test
    void createTask_actorAware_publishesAddedActivity() {
        final UUID actor = UUID.randomUUID();
        final TaskList list = persistedList();
        when(listRepository.findById(list.getId())).thenReturn(Optional.of(list));
        when(taskRepository.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));

        taskService.createTask(actor, list.getId(), "Buy milk");

        final ListActivityEvent event = captureActivity();
        assertThat(event.listId()).isEqualTo(list.getId());
        assertThat(event.actorId()).isEqualTo(actor);
        assertThat(event.kind()).isEqualTo(ListActivityEvent.Kind.ADDED);
        assertThat(event.count()).isEqualTo(1);
    }

    @Test
    void createTasks_actorAware_publishesAddedActivity_withCount() {
        final UUID actor = UUID.randomUUID();
        final TaskList list = persistedList();
        when(listRepository.findById(list.getId())).thenReturn(Optional.of(list));
        when(taskRepository.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));

        taskService.createTasks(actor, list.getId(), List.of("milk", "bread", "eggs"));

        final ListActivityEvent event = captureActivity();
        assertThat(event.kind()).isEqualTo(ListActivityEvent.Kind.ADDED);
        assertThat(event.count()).isEqualTo(3);
    }

    @Test
    void markDone_actorAware_publishesCompletedActivity_whenChanged() {
        final UUID actor = UUID.randomUUID();
        final UUID id = UUID.randomUUID();
        final Task task = todo(id);
        when(taskRepository.findById(id)).thenReturn(Optional.of(task));

        taskService.markDone(actor, id);

        final ListActivityEvent event = captureActivity();
        assertThat(event.actorId()).isEqualTo(actor);
        assertThat(event.kind()).isEqualTo(ListActivityEvent.Kind.COMPLETED);
        assertThat(event.count()).isEqualTo(1);
    }

    @Test
    void markDone_actorAware_publishesNoActivity_whenAlreadyDone() {
        final UUID actor = UUID.randomUUID();
        final UUID id = UUID.randomUUID();
        final Task task = todo(id);
        task.setStatus(TaskStatus.DONE);
        when(taskRepository.findById(id)).thenReturn(Optional.of(task));

        taskService.markDone(actor, id);

        assertThat(activityEvents()).isEmpty();
    }

    @Test
    void reopen_actorAware_publishesNoActivity() {
        final UUID actor = UUID.randomUUID();
        final UUID id = UUID.randomUUID();
        final Task task = todo(id);
        task.setStatus(TaskStatus.DONE);
        when(taskRepository.findById(id)).thenReturn(Optional.of(task));

        taskService.reopen(actor, id);

        assertThat(activityEvents()).isEmpty();
    }

    @Test
    void saveForLater_defersToActorsBucket_archivesAndMarksDeferred_andAnnouncesChange() {
        // Arrange
        final UUID actor = UUID.randomUUID();
        final UUID id = UUID.randomUUID();
        final Task task = todo(id);
        when(taskRepository.findById(id)).thenReturn(Optional.of(task));
        final PendingItem pending = PendingItem.defer(actor, "do something", task.getListId(), List.of(), id);
        when(pendingItemService.defer(actor, "do something", task.getListId(), id)).thenReturn(pending);

        // Act
        final PendingItem result = taskService.saveForLater(actor, id);

        // Assert
        verify(permissionService).requireCanDeferItems(actor, task.getListId());
        verify(pendingItemService).defer(actor, "do something", task.getListId(), id);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.DEFERRED);
        assertThat(task.getArchivedAt()).isNotNull();
        assertThat(result).isSameAs(pending);
        verify(eventPublisher).publishEvent(new ListChangedEvent(task.getListId()));
    }

    @Test
    void saveForLater_allowsAMemberWhoIsNotTheOwner_toDefer() {
        // Arrange — a shared list owned by someone else; the acting member passes the MEMBER+ guard.
        final UUID member = UUID.randomUUID();
        final UUID id = UUID.randomUUID();
        final Task task = todo(id);
        when(taskRepository.findById(id)).thenReturn(Optional.of(task));
        when(pendingItemService.defer(member, "do something", task.getListId(), id))
                .thenReturn(PendingItem.defer(member, "do something", task.getListId(), List.of(), id));

        // Act
        taskService.saveForLater(member, id);

        // Assert — the pending item is owned by the acting member, not the list owner.
        assertThat(task.getOwnerId()).isNotEqualTo(member);
        verify(pendingItemService).defer(member, "do something", task.getListId(), id);
    }

    @Test
    void saveForLater_throws_andDefersNothing_whenPermissionDenied() {
        // Arrange
        final UUID actor = UUID.randomUUID();
        final UUID id = UUID.randomUUID();
        final Task task = todo(id);
        when(taskRepository.findById(id)).thenReturn(Optional.of(task));
        doThrow(new ListPermissionDeniedException(actor, task.getListId(), "defer item", null))
                .when(permissionService).requireCanDeferItems(actor, task.getListId());

        // Act + Assert
        assertThatThrownBy(() -> taskService.saveForLater(actor, id))
                .isInstanceOf(ListPermissionDeniedException.class);
        verify(pendingItemService, never()).defer(any(), any(), any(), any());
        assertThat(task.getStatus()).isEqualTo(TaskStatus.TODO);
        assertThat(task.getArchivedAt()).isNull();
    }

    @Test
    void saveForLater_throws_andDefersNothing_whenTaskAlreadyArchived() {
        // Arrange — a replayed save on a task already deferred away.
        final UUID actor = UUID.randomUUID();
        final UUID id = UUID.randomUUID();
        final Task task = todo(id);
        task.setArchivedAt(Instant.now());
        when(taskRepository.findById(id)).thenReturn(Optional.of(task));

        // Act + Assert
        assertThatThrownBy(() -> taskService.saveForLater(actor, id))
                .isInstanceOf(IllegalStateException.class);
        verify(pendingItemService, never()).defer(any(), any(), any(), any());
    }

    private ListActivityEvent captureActivity() {
        final List<ListActivityEvent> events = activityEvents();
        assertThat(events).hasSize(1);
        return events.get(0);
    }

    private List<ListActivityEvent> activityEvents() {
        final ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, org.mockito.Mockito.atLeast(0)).publishEvent(captor.capture());
        return captor.getAllValues().stream()
                .filter(ListActivityEvent.class::isInstance)
                .map(ListActivityEvent.class::cast)
                .toList();
    }
}
