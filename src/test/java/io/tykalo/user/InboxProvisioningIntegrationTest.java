package io.tykalo.user;

import static org.assertj.core.api.Assertions.assertThat;

import io.tykalo.AbstractIntegrationTest;
import io.tykalo.list.ListRepository;
import io.tykalo.list.ListType;
import io.tykalo.list.NudgerDefaultPolicy;
import io.tykalo.list.TaskList;
import io.tykalo.telegram.TelegramUpdateFixtures;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Exercises the full registration side effect end-to-end: {@link UserService#findOrCreate} fires a
 * {@code UserCreatedEvent}, the list domain's listener provisions an Inbox, and it all commits
 * atomically against the real Flyway schema. Also proves idempotency: a repeated /start adds nothing.
 */
class InboxProvisioningIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private UserService userService;

    @Autowired
    private ListRepository listRepository;

    @Test
    void findOrCreate_provisionsExactlyOneInbox_forANewUser() {
        // Arrange
        final var update = TelegramUpdateFixtures.command("/start", 770_001L, "alice", "uk");

        // Act
        final User user = userService.findOrCreate(update);

        // Assert
        final List<TaskList> lists = listRepository.findByOwnerId(user.getId());
        assertThat(lists).singleElement().satisfies(inbox -> {
            assertThat(inbox.getName()).isEqualTo("Inbox");
            assertThat(inbox.getType()).isEqualTo(ListType.INBOX);
            assertThat(inbox.getNudgerDefaultPolicy()).isEqualTo(NudgerDefaultPolicy.OFF);
        });
    }

    @Test
    void findOrCreate_isIdempotent_repeatedStartAddsNoSecondInbox() {
        // Arrange
        final var update = TelegramUpdateFixtures.command("/start", 770_002L, "bob", "uk");

        // Act
        final User first = userService.findOrCreate(update);
        final User second = userService.findOrCreate(update);

        // Assert
        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(listRepository.findByOwnerId(first.getId())).hasSize(1);
    }
}
