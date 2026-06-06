package io.tykalo.list;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tykalo.user.User;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TaskListFactoryTest {

    private static User persistedOwner() {
        final User owner = User.create(123L, "bob", ZoneId.of("Europe/Kyiv"), "uk");
        owner.setId(UUID.randomUUID());
        return owner;
    }

    @Test
    void checklist_hasTypeChecklist_andNudgersOff() {
        // Arrange
        final User owner = persistedOwner();

        // Act
        final TaskList list = TaskList.checklist(owner, "Groceries");

        // Assert
        assertThat(list.getType()).isEqualTo(ListType.CHECKLIST);
        assertThat(list.getNudgerDefaultPolicy()).isEqualTo(NudgerDefaultPolicy.OFF);
        assertThat(list.getName()).isEqualTo("Groceries");
        assertThat(list.getOwnerId()).isEqualTo(owner.getId());
    }

    @Test
    void routine_hasTypeRoutine_andNudgersOptIn() {
        // Act
        final TaskList list = TaskList.routine(persistedOwner(), "Morning");

        // Assert
        assertThat(list.getType()).isEqualTo(ListType.ROUTINE);
        assertThat(list.getNudgerDefaultPolicy()).isEqualTo(NudgerDefaultPolicy.OPT_IN);
    }

    @Test
    void project_hasTypeProject_andNudgersPerTask() {
        // Act
        final TaskList list = TaskList.project(persistedOwner(), "Tykalo");

        // Assert
        assertThat(list.getType()).isEqualTo(ListType.PROJECT);
        assertThat(list.getNudgerDefaultPolicy()).isEqualTo(NudgerDefaultPolicy.ON_PER_TASK);
    }

    @Test
    void inbox_defaultsNameToInbox_andNudgersOff() {
        // Act
        final TaskList list = TaskList.inbox(persistedOwner());

        // Assert
        assertThat(list.getType()).isEqualTo(ListType.INBOX);
        assertThat(list.getName()).isEqualTo("Inbox");
        assertThat(list.getNudgerDefaultPolicy()).isEqualTo(NudgerDefaultPolicy.OFF);
    }

    @Test
    void create_rejectsUnpersistedOwner() {
        // Arrange — owner without an id (not yet saved)
        final User owner = User.create(123L, "bob", ZoneId.of("Europe/Kyiv"), "uk");

        // Act / Assert
        assertThatThrownBy(() -> TaskList.checklist(owner, "X"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("owner must be persisted");
    }
}
