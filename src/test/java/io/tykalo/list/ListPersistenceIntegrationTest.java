package io.tykalo.list;

import static org.assertj.core.api.Assertions.assertThat;

import io.tykalo.AbstractIntegrationTest;
import io.tykalo.user.User;
import io.tykalo.user.UserRepository;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Persists a {@link TaskList} against the real Flyway-migrated schema, proving the entity maps
 * cleanly onto {@code V2__lists_table.sql} and that the enums satisfy the table CHECK constraints.
 */
class ListPersistenceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ListRepository listRepository;

    @Autowired
    private UserRepository userRepository;

    private User savedOwner(final long tgChatId) {
        return userRepository.save(User.create(tgChatId, "owner", ZoneId.of("Europe/Kyiv"), "uk"));
    }

    @Test
    void savesAndReadsBackProject_matchingTheSchema() {
        // Arrange
        final User owner = savedOwner(900_001L);

        // Act
        final TaskList saved = listRepository.save(TaskList.project(owner, "Tykalo"));
        final Optional<TaskList> found = listRepository.findById(saved.getId());

        // Assert
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(found).isPresent().get().satisfies(list -> {
            assertThat(list.getOwnerId()).isEqualTo(owner.getId());
            assertThat(list.getName()).isEqualTo("Tykalo");
            assertThat(list.getType()).isEqualTo(ListType.PROJECT);
            assertThat(list.getNudgerDefaultPolicy()).isEqualTo(NudgerDefaultPolicy.ON_PER_TASK);
            assertThat(list.getArchivedAt()).isNull();
        });
    }

    @Test
    void findByOwnerId_returnsOnlyThatOwnersLists() {
        // Arrange
        final User owner = savedOwner(900_002L);
        final User other = savedOwner(900_003L);
        listRepository.save(TaskList.checklist(owner, "Groceries"));
        listRepository.save(TaskList.inbox(owner));
        listRepository.save(TaskList.checklist(other, "Theirs"));

        // Act
        final List<TaskList> ownersLists = listRepository.findByOwnerId(owner.getId());

        // Assert
        assertThat(ownersLists).hasSize(2)
                .extracting(TaskList::getName)
                .containsExactlyInAnyOrder("Groceries", "Inbox");
    }
}
