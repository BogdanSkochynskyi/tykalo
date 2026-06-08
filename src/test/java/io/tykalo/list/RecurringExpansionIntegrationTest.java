package io.tykalo.list;

import static org.assertj.core.api.Assertions.assertThat;

import io.tykalo.AbstractIntegrationTest;
import io.tykalo.user.User;
import io.tykalo.user.UserRepository;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Drives the full {@link TaskService#completeTask} recurrence-expansion path (TK-146) against the real
 * Flyway-migrated schema: completing a recurring, due task must persist exactly one next instance in
 * the same transaction, while a non-recurring task must not.
 */
class RecurringExpansionIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TaskService taskService;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private ListRepository listRepository;

    @Autowired
    private UserRepository userRepository;

    private TaskList savedList(final long tgChatId) {
        final User owner = userRepository.save(User.create(tgChatId, "owner", ZoneId.of("Europe/Kyiv"), "uk"));
        return listRepository.save(TaskList.checklist(owner, "Habits"));
    }

    @Test
    void completeTask_persistsNextDailyInstance_inSameTransaction() {
        // Arrange — daily task due Mon 2026-06-08 09:00 Kyiv
        final TaskList list = savedList(970_001L);
        final Instant due = Instant.parse("2026-06-08T06:00:00Z");
        final Task original = taskService.createTask(list.getId(), "Drink water", due, "FREQ=DAILY");

        // Act
        taskService.completeTask(original.getId());

        // Assert — the original is DONE and a fresh TODO exists one day later
        final List<Task> all = taskRepository.findByListId(list.getId());
        assertThat(all).hasSize(2);
        final Task next = all.stream()
                .filter(t -> t.getStatus() == TaskStatus.TODO)
                .findFirst()
                .orElseThrow();
        assertThat(next.getId()).isNotEqualTo(original.getId());
        assertThat(next.getTitle()).isEqualTo("Drink water");
        assertThat(next.getRecurrenceRule()).contains("FREQ=DAILY");
        assertThat(next.getDueAt()).contains(Instant.parse("2026-06-09T06:00:00Z"));
        assertThat(next.getArchivedAt()).isNull();
    }

    @Test
    void completeTask_doesNotSpawn_forNonRecurringTask() {
        // Arrange
        final TaskList list = savedList(970_002L);
        final Task original = taskService.createTask(list.getId(), "One-off", Instant.parse("2026-06-08T06:00:00Z"));

        // Act
        taskService.completeTask(original.getId());

        // Assert
        assertThat(taskRepository.findByListId(list.getId())).hasSize(1);
    }
}
