package io.tykalo.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tykalo.AbstractIntegrationTest;
import io.tykalo.list.ListRepository;
import io.tykalo.list.Task;
import io.tykalo.list.TaskList;
import io.tykalo.list.TaskRepository;
import io.tykalo.list.TaskStatus;
import io.tykalo.user.User;
import io.tykalo.user.UserRepository;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Exercises {@link ReminderLogRepository} and {@link TaskRepository#findOverdueProjectTasks} against
 * the real Flyway-migrated schema (V7), proving the {@code UNIQUE(task_id, level)} backstop and the
 * overdue-PROJECT filtering. Uses the 960_00x tg_chat_id range (the singleton Postgres is shared and
 * never reset between integration-test classes).
 */
class ReminderLogPersistenceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ReminderLogRepository reminderLogRepository;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private ListRepository listRepository;

    @Autowired
    private UserRepository userRepository;

    private User savedOwner(final long tgChatId) {
        return userRepository.save(User.create(tgChatId, "owner", ZoneId.of("Europe/Kyiv"), "uk"));
    }

    private Task saveTask(final TaskList list, final String title, final Instant dueAt) {
        final Task task = Task.create(list, title);
        task.setDueAt(dueAt);
        return taskRepository.save(task);
    }

    @Test
    void uniqueConstraint_rejectsSameLevelTwiceForSameTask() {
        // Arrange
        final User owner = savedOwner(960_001L);
        final TaskList project = listRepository.save(TaskList.project(owner, "Launch"));
        final Task task = saveTask(project, "ship", Instant.parse("2030-01-07T00:00:00Z"));
        reminderLogRepository.saveAndFlush(ReminderLog.of(task.getId(), ReminderLevel.L1, Instant.now()));

        // Act + Assert — a second L1 for the same task violates UNIQUE(task_id, level)
        assertThatThrownBy(() ->
                reminderLogRepository.saveAndFlush(ReminderLog.of(task.getId(), ReminderLevel.L1, Instant.now())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void uniqueConstraint_allowsDifferentLevelsForSameTask() {
        // Arrange
        final User owner = savedOwner(960_002L);
        final TaskList project = listRepository.save(TaskList.project(owner, "Launch"));
        final Task task = saveTask(project, "ship", Instant.parse("2030-01-07T00:00:00Z"));

        // Act
        reminderLogRepository.saveAndFlush(ReminderLog.of(task.getId(), ReminderLevel.L1, Instant.now()));
        reminderLogRepository.saveAndFlush(ReminderLog.of(task.getId(), ReminderLevel.L2, Instant.now()));

        // Assert
        final List<ReminderLog> logs = reminderLogRepository.findByTaskIdIn(List.of(task.getId()));
        assertThat(logs).extracting(ReminderLog::getLevel).containsExactlyInAnyOrder(1, 2);
    }

    @Test
    void findByTaskIdIn_returnsOnlyRequestedTasks() {
        // Arrange
        final User owner = savedOwner(960_003L);
        final TaskList project = listRepository.save(TaskList.project(owner, "Launch"));
        final Task wanted = saveTask(project, "wanted", Instant.parse("2030-01-07T00:00:00Z"));
        final Task other = saveTask(project, "other", Instant.parse("2030-01-07T00:00:00Z"));
        reminderLogRepository.saveAndFlush(ReminderLog.of(wanted.getId(), ReminderLevel.L1, Instant.now()));
        reminderLogRepository.saveAndFlush(ReminderLog.of(other.getId(), ReminderLevel.L1, Instant.now()));

        // Act
        final List<ReminderLog> logs = reminderLogRepository.findByTaskIdIn(List.of(wanted.getId()));

        // Assert
        assertThat(logs).extracting(ReminderLog::getTaskId).containsExactly(wanted.getId());
    }

    @Test
    void findOverdueProjectTasks_returnsOnlyOverdueProjectTodoTasks() {
        // Arrange
        final User owner = savedOwner(960_004L);
        final TaskList project = listRepository.save(TaskList.project(owner, "Launch"));
        final TaskList checklist = listRepository.save(TaskList.checklist(owner, "Groceries"));
        final Instant now = Instant.parse("2030-01-07T12:00:00Z");
        final Instant overdue = now.minus(3, ChronoUnit.HOURS);
        final Instant future = now.plus(3, ChronoUnit.HOURS);

        final Task wanted = saveTask(project, "overdue project", overdue);
        saveTask(project, "future project", future);
        saveTask(checklist, "overdue checklist", overdue);

        final Task done = saveTask(project, "done", overdue);
        done.setStatus(TaskStatus.DONE);
        taskRepository.save(done);

        final Task archived = saveTask(project, "archived", overdue);
        archived.setArchivedAt(Instant.now());
        taskRepository.save(archived);

        // Act
        final List<Task> result = taskRepository.findOverdueProjectTasks(now);

        // Assert — only the live, TODO, overdue PROJECT task
        assertThat(result).extracting(Task::getId).contains(wanted.getId());
        assertThat(result).extracting(Task::getId)
                .doesNotContain(done.getId(), archived.getId());
        assertThat(result).allSatisfy(t -> {
            assertThat(t.getStatus()).isEqualTo(TaskStatus.TODO);
            assertThat(t.getArchivedAt()).isNull();
        });
    }

    @Test
    void findOverdueProjectTasks_excludesArchivedProjectLists() {
        // Arrange
        final User owner = savedOwner(960_005L);
        final TaskList archivedProject = TaskList.project(owner, "Old");
        archivedProject.setArchivedAt(Instant.now());
        listRepository.save(archivedProject);
        final Instant now = Instant.parse("2030-01-07T12:00:00Z");
        final Task hidden = saveTask(archivedProject, "in archived list", now.minus(3, ChronoUnit.HOURS));

        // Act
        final List<Task> result = taskRepository.findOverdueProjectTasks(now);

        // Assert
        assertThat(result).extracting(Task::getId).doesNotContain(hidden.getId());
    }

    @Test
    void findOverdueProjectTasks_excludesChecklistTasks() {
        // Arrange — a checklist task overdue must never be reminded
        final User owner = savedOwner(960_006L);
        final TaskList checklist = listRepository.save(TaskList.checklist(owner, "Groceries"));
        final Instant now = Instant.parse("2030-01-07T12:00:00Z");
        final Task milk = saveTask(checklist, "milk", now.minus(5, ChronoUnit.HOURS));

        // Act
        final List<Task> result = taskRepository.findOverdueProjectTasks(now);

        // Assert
        assertThat(result).extracting(Task::getId).doesNotContain(milk.getId());
    }
}
