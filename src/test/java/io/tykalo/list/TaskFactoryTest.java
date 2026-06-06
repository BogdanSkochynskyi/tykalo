package io.tykalo.list;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tykalo.user.User;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TaskFactoryTest {

    private static TaskList persistedList() {
        final User owner = User.create(123L, "bob", ZoneId.of("Europe/Kyiv"), "uk");
        owner.setId(UUID.randomUUID());
        final TaskList list = TaskList.checklist(owner, "Groceries");
        list.setId(UUID.randomUUID());
        return list;
    }

    @Test
    void create_buildsTodoCheckbox_inheritingListAndOwner() {
        // Arrange
        final TaskList list = persistedList();

        // Act
        final Task task = Task.create(list, "Buy milk");

        // Assert
        assertThat(task.getTitle()).isEqualTo("Buy milk");
        assertThat(task.getStatus()).isEqualTo(TaskStatus.TODO);
        assertThat(task.getListId()).isEqualTo(list.getId());
        assertThat(task.getOwnerId()).isEqualTo(list.getOwnerId());
    }

    @Test
    void create_leavesOptionalFieldsEmpty() {
        // Act
        final Task task = Task.create(persistedList(), "Buy milk");

        // Assert
        assertThat(task.getDescription()).isEmpty();
        assertThat(task.getDueAt()).isEmpty();
        assertThat(task.getPriority()).isEmpty();
        assertThat(task.getRecurrenceRule()).isEmpty();
        assertThat(task.getGcalEventId()).isEmpty();
        assertThat(task.getTags()).isEmpty();
    }

    @Test
    void create_rejectsUnpersistedList() {
        // Arrange — list without an id (not yet saved)
        final User owner = User.create(123L, "bob", ZoneId.of("Europe/Kyiv"), "uk");
        owner.setId(UUID.randomUUID());
        final TaskList list = TaskList.checklist(owner, "Groceries");

        // Act / Assert
        assertThatThrownBy(() -> Task.create(list, "X"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("list must be persisted");
    }
}
