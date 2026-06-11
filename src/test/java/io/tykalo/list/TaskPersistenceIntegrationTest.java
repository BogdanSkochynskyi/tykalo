package io.tykalo.list;

import static org.assertj.core.api.Assertions.assertThat;

import io.tykalo.AbstractIntegrationTest;
import io.tykalo.user.User;
import io.tykalo.user.UserRepository;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Persists a {@link Task} against the real Flyway-migrated schema, proving the entity maps cleanly
 * onto {@code V3__tasks_table.sql} — including the Postgres {@code text[]} tags array and the enum
 * CHECK constraints — and that the repository queries behave against real SQL.
 */
class TaskPersistenceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private ListRepository listRepository;

    @Autowired
    private UserRepository userRepository;

    private TaskList savedList(final long tgChatId) {
        final User owner = userRepository.save(User.create(tgChatId, "owner", ZoneId.of("Europe/Kyiv"), "uk"));
        return listRepository.save(TaskList.checklist(owner, "Groceries"));
    }

    @Test
    void savesAndReadsBackFullTask_matchingTheSchema() {
        // Arrange
        final TaskList list = savedList(910_001L);
        final Instant due = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS);
        final Task task = Task.create(list, "Buy milk");
        task.setDescription("2% only");
        task.setDueAt(due);
        task.setPriority(Priority.HIGH);
        task.setRecurrenceRule("FREQ=WEEKLY");
        task.setGcalEventId("evt-1");
        task.setTags(List.of("home", "urgent"));

        // Act
        final Task saved = taskRepository.save(task);
        final Optional<Task> found = taskRepository.findById(saved.getId());

        // Assert
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
        assertThat(found).isPresent().get().satisfies(t -> {
            assertThat(t.getListId()).isEqualTo(list.getId());
            assertThat(t.getOwnerId()).isEqualTo(list.getOwnerId());
            assertThat(t.getTitle()).isEqualTo("Buy milk");
            assertThat(t.getStatus()).isEqualTo(TaskStatus.TODO);
            assertThat(t.getDescription()).contains("2% only");
            assertThat(t.getDueAt()).contains(due);
            assertThat(t.getPriority()).contains(Priority.HIGH);
            assertThat(t.getRecurrenceRule()).contains("FREQ=WEEKLY");
            assertThat(t.getGcalEventId()).contains("evt-1");
            assertThat(t.getTags()).containsExactly("home", "urgent");
            assertThat(t.getArchivedAt()).isNull();
        });
    }

    @Test
    void titleOnlyTask_persistsWithEmptyOptionalsAndEmptyTags() {
        // Arrange
        final TaskList list = savedList(910_002L);

        // Act
        final Task saved = taskRepository.save(Task.create(list, "Just a checkbox"));
        final Optional<Task> found = taskRepository.findById(saved.getId());

        // Assert
        assertThat(found).isPresent().get().satisfies(t -> {
            assertThat(t.getDescription()).isEmpty();
            assertThat(t.getDueAt()).isEmpty();
            assertThat(t.getPriority()).isEmpty();
            assertThat(t.getTags()).isEmpty();
        });
    }

    @Test
    void findByOwnerIdAndStatus_returnsOnlyMatching() {
        // Arrange
        final TaskList list = savedList(910_003L);
        taskRepository.save(Task.create(list, "open"));
        final Task done = Task.create(list, "finished");
        done.setStatus(TaskStatus.DONE);
        taskRepository.save(done);

        // Act
        final List<Task> open = taskRepository.findByOwnerIdAndStatus(list.getOwnerId(), TaskStatus.TODO);

        // Assert
        assertThat(open).extracting(Task::getTitle).containsExactly("open");
    }

    @Test
    void findByListId_returnsOnlyThatListsTasks() {
        // Arrange
        final TaskList list = savedList(910_004L);
        final TaskList other = listRepository.save(
                TaskList.checklist(userRepository.findById(list.getOwnerId()).orElseThrow(), "Other"));
        taskRepository.save(Task.create(list, "mine"));
        taskRepository.save(Task.create(other, "theirs"));

        // Act
        final List<Task> tasks = taskRepository.findByListId(list.getId());

        // Assert
        assertThat(tasks).extracting(Task::getTitle).containsExactly("mine");
    }

    @Test
    void findOverdue_returnsOnlyPastDueTodoTasks() {
        // Arrange
        final TaskList list = savedList(910_005L);
        final Instant now = Instant.now();

        final Task overdue = Task.create(list, "overdue");
        overdue.setDueAt(now.minus(2, ChronoUnit.HOURS));
        taskRepository.save(overdue);

        final Task future = Task.create(list, "future");
        future.setDueAt(now.plus(2, ChronoUnit.HOURS));
        taskRepository.save(future);

        final Task overdueButDone = Task.create(list, "done");
        overdueButDone.setDueAt(now.minus(2, ChronoUnit.HOURS));
        overdueButDone.setStatus(TaskStatus.DONE);
        taskRepository.save(overdueButDone);

        final Task overdueButArchived = Task.create(list, "archived");
        overdueButArchived.setDueAt(now.minus(2, ChronoUnit.HOURS));
        overdueButArchived.setArchivedAt(now);
        taskRepository.save(overdueButArchived);

        // Act — findOverdue is unscoped and the singleton container is shared across integration
        // classes, so narrow to this test's own list before asserting (mirrors findByListId above).
        final List<Task> result = taskRepository.findOverdue(now).stream()
                .filter(task -> task.getListId().equals(list.getId()))
                .toList();

        // Assert
        assertThat(result).extracting(Task::getTitle).containsExactly("overdue");
    }
}
