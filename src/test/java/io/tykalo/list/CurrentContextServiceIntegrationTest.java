package io.tykalo.list;

import static org.assertj.core.api.Assertions.assertThat;

import io.tykalo.AbstractIntegrationTest;
import io.tykalo.user.User;
import io.tykalo.user.UserRepository;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Exercises {@link CurrentContextService} against real Redis and Postgres containers — proving the
 * new Spring Data Redis wiring actually connects, the 24h TTL is applied, and the Inbox fallback
 * resolves through the live schema.
 */
class CurrentContextServiceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private CurrentContextService currentContext;

    @Autowired
    private ListService listService;

    @Autowired
    private ListRepository listRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private StringRedisTemplate redis;

    private User savedOwner(final long tgChatId) {
        return userRepository.save(User.create(tgChatId, "owner", ZoneId.of("Europe/Kyiv"), "uk"));
    }

    @Test
    void setGetClear_roundTripsThroughRedis_withTtl() {
        // Arrange
        final User owner = savedOwner(920_001L);
        final UUID userId = Objects.requireNonNull(owner.getId());
        final TaskList project = listRepository.save(TaskList.project(owner, "Tykalo"));

        // Act
        currentContext.set(userId, Objects.requireNonNull(project.getId()));

        // Assert
        assertThat(currentContext.get(userId)).contains(project.getId());
        final Long ttlSeconds = redis.getExpire("user:" + userId + ":currentList");
        assertThat(ttlSeconds).isNotNull();
        assertThat(Duration.ofSeconds(ttlSeconds)).isBetween(Duration.ofHours(23), Duration.ofHours(24));

        currentContext.clear(userId);
        assertThat(currentContext.get(userId)).isEmpty();
    }

    @Test
    void resolveCurrentList_returnsStoredList_whenActive() {
        // Arrange
        final User owner = savedOwner(920_002L);
        final UUID userId = Objects.requireNonNull(owner.getId());
        listRepository.save(TaskList.inbox(owner));
        final TaskList work = listRepository.save(TaskList.project(owner, "Work"));
        currentContext.set(userId, Objects.requireNonNull(work.getId()));

        // Act / Assert
        assertThat(currentContext.resolveCurrentList(userId))
                .get()
                .extracting(TaskList::getName)
                .isEqualTo("Work");
    }

    @Test
    void resolveCurrentList_clearsStaleKeyAndFallsBackToInbox_afterListArchived() {
        // Arrange
        final User owner = savedOwner(920_003L);
        final UUID userId = Objects.requireNonNull(owner.getId());
        listRepository.save(TaskList.inbox(owner));
        final TaskList groceries = listRepository.save(TaskList.checklist(owner, "Groceries"));
        currentContext.set(userId, Objects.requireNonNull(groceries.getId()));
        listService.deleteList(groceries.getId());

        // Act
        final var resolved = currentContext.resolveCurrentList(userId);

        // Assert
        assertThat(resolved).get().extracting(TaskList::getType).isEqualTo(ListType.INBOX);
        assertThat(currentContext.get(userId)).isEmpty();
    }
}
