package io.tykalo.list;

import static org.assertj.core.api.Assertions.assertThat;

import io.tykalo.AbstractIntegrationTest;
import io.tykalo.user.User;
import io.tykalo.user.UserRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Exercises {@link TaskRepository#findProjectTasksDueBetween} — the morning-digest query — against the
 * real Flyway-migrated schema, proving the Task↔TaskList theta-join filters to live {@code PROJECT}
 * lists, TODO non-archived tasks inside the window, ordered by due time.
 */
class MorningDigestQueryIntegrationTest extends AbstractIntegrationTest {

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
    void findProjectTasksDueBetween_returnsOnlyProjectTodoTasksInWindow_orderedByDue() {
        // Arrange
        final User owner = savedOwner(940_001L);
        final TaskList project = listRepository.save(TaskList.project(owner, "Launch"));
        final TaskList checklist = listRepository.save(TaskList.checklist(owner, "Groceries"));
        final Instant start = Instant.parse("2030-01-07T00:00:00Z");
        final Instant end = start.plus(1, ChronoUnit.DAYS);

        final Task later = saveTask(project, "later", start.plus(10, ChronoUnit.HOURS));
        final Task earlier = saveTask(project, "earlier", start.plus(2, ChronoUnit.HOURS));
        saveTask(checklist, "wrong list type", start.plus(3, ChronoUnit.HOURS));
        saveTask(project, "outside window", end.plus(1, ChronoUnit.HOURS));

        final Task done = saveTask(project, "done", start.plus(4, ChronoUnit.HOURS));
        done.setStatus(TaskStatus.DONE);
        taskRepository.save(done);

        final Task archived = saveTask(project, "archived", start.plus(5, ChronoUnit.HOURS));
        archived.setArchivedAt(Instant.now());
        taskRepository.save(archived);

        // Act
        final List<Task> result = taskRepository.findProjectTasksDueBetween(owner.getId(), start, end);

        // Assert — only the two in-window PROJECT TODO tasks, earliest first
        assertThat(result).extracting(Task::getId).containsExactly(earlier.getId(), later.getId());
    }

    @Test
    void findProjectTasksDueBetween_excludesArchivedProjectLists() {
        // Arrange
        final User owner = savedOwner(940_002L);
        final TaskList archivedProject = TaskList.project(owner, "Old project");
        archivedProject.setArchivedAt(Instant.now());
        listRepository.save(archivedProject);
        final Instant start = Instant.parse("2030-01-07T00:00:00Z");
        final Instant end = start.plus(1, ChronoUnit.DAYS);
        saveTask(archivedProject, "in archived list", start.plus(2, ChronoUnit.HOURS));

        // Act
        final List<Task> result = taskRepository.findProjectTasksDueBetween(owner.getId(), start, end);

        // Assert
        assertThat(result).isEmpty();
    }

    @Test
    void findProjectTasksDueBetween_scopesToOwner() {
        // Arrange — another owner's in-window project task must not leak
        final User owner = savedOwner(940_003L);
        final User other = savedOwner(940_004L);
        final TaskList ownerProject = listRepository.save(TaskList.project(owner, "Mine"));
        final TaskList otherProject = listRepository.save(TaskList.project(other, "Theirs"));
        final Instant start = Instant.parse("2030-01-07T00:00:00Z");
        final Instant end = start.plus(1, ChronoUnit.DAYS);
        final Task mine = saveTask(ownerProject, "mine", start.plus(2, ChronoUnit.HOURS));
        saveTask(otherProject, "theirs", start.plus(2, ChronoUnit.HOURS));

        // Act
        final List<Task> result = taskRepository.findProjectTasksDueBetween(owner.getId(), start, end);

        // Assert
        assertThat(result).extracting(Task::getId).containsExactly(mine.getId());
    }
}
