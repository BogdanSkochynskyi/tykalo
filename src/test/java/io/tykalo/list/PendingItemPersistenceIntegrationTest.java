package io.tykalo.list;

import static org.assertj.core.api.Assertions.assertThat;

import io.tykalo.AbstractIntegrationTest;
import io.tykalo.user.User;
import io.tykalo.user.UserRepository;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Exercises {@link PendingItem} and {@link PendingItemRepository} against the real Flyway-migrated schema
 * (V20): the {@code deferred_at}/tags defaults, the native tag-overlap query (Postgres {@code &&}), the
 * ON DELETE CASCADE to users and the ON DELETE SET NULL to lists. Uses the 1_030_00x tg_chat_id range
 * (the singleton Postgres is shared and never reset between integration-test classes).
 */
class PendingItemPersistenceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private PendingItemRepository pendingItemRepository;

    @Autowired
    private ListRepository listRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcClient jdbcClient;

    private User savedUser(final long tgChatId, final String username) {
        return userRepository.save(User.create(tgChatId, username, ZoneId.of("Europe/Kyiv"), "uk"));
    }

    private TaskList savedListWithTags(final User owner, final String name, final List<String> tags) {
        final TaskList list = TaskList.checklist(owner, name);
        list.setTags(tags);
        return listRepository.save(list);
    }

    @Test
    void defer_persistsWithStampedDeferredAt_andDefaults() {
        // Arrange
        final User user = savedUser(1_030_001L, "defer");

        // Act
        final PendingItem saved =
                pendingItemRepository.save(PendingItem.defer(user.getId(), "Buy milk", null, List.of(), null));
        final PendingItem reloaded = pendingItemRepository.findById(saved.getId()).orElseThrow();

        // Assert
        assertThat(reloaded.getTitle()).isEqualTo("Buy milk");
        assertThat(reloaded.getDeferredAt()).isNotNull();
        assertThat(reloaded.getOriginalListTags()).isEmpty();
        assertThat(reloaded.getOriginalListId()).isEmpty();
        assertThat(reloaded.getSourceTaskId()).isEmpty();
        assertThat(reloaded.getDeferredUntil()).isEmpty();
    }

    @Test
    void deferWithProvenance_persistsListIdTagsAndSourceTask() {
        // Arrange
        final User user = savedUser(1_030_002L, "provenance");
        final TaskList list = savedListWithTags(user, "Groceries", List.of("shopping", "home"));
        final UUID sourceTaskId = UUID.randomUUID();

        // Act
        final PendingItem saved = pendingItemRepository.save(
                PendingItem.defer(user.getId(), "Buy milk", list.getId(), list.getTags(), sourceTaskId));
        final PendingItem reloaded = pendingItemRepository.findById(saved.getId()).orElseThrow();

        // Assert
        assertThat(reloaded.getOriginalListId()).contains(list.getId());
        assertThat(reloaded.getOriginalListTags()).containsExactly("shopping", "home");
        assertThat(reloaded.getSourceTaskId()).contains(sourceTaskId);
    }

    @Test
    void findByUserIdAndTagsOverlapping_returnsOnlyItemsSharingATag() {
        // Arrange
        final User user = savedUser(1_030_003L, "tags");
        final PendingItem shopping = pendingItemRepository.save(
                PendingItem.defer(user.getId(), "Milk", null, List.of("shopping", "home"), null));
        pendingItemRepository.save(PendingItem.defer(user.getId(), "Standup", null, List.of("work"), null));

        // Act — overlap on "home" matches only the first item
        final List<PendingItem> matches =
                pendingItemRepository.findByUserIdAndTagsOverlapping(user.getId(), new String[] {"home", "garden"});

        // Assert
        assertThat(matches).extracting(PendingItem::getId).containsExactly(shopping.getId());
    }

    @Test
    void findByUserIdAndTagsOverlapping_returnsEmpty_whenNoTagOverlaps() {
        // Arrange
        final User user = savedUser(1_030_004L, "nomatch");
        pendingItemRepository.save(PendingItem.defer(user.getId(), "Milk", null, List.of("shopping"), null));

        // Act + Assert
        assertThat(pendingItemRepository.findByUserIdAndTagsOverlapping(user.getId(), new String[] {"work"}))
                .isEmpty();
    }

    @Test
    void findByUserIdAndTagsOverlapping_isScopedToTheUser() {
        // Arrange — same tag, different users
        final User mine = savedUser(1_030_005L, "mine");
        final User other = savedUser(1_030_006L, "other");
        final PendingItem ofMine =
                pendingItemRepository.save(PendingItem.defer(mine.getId(), "Mine", null, List.of("shopping"), null));
        pendingItemRepository.save(PendingItem.defer(other.getId(), "Theirs", null, List.of("shopping"), null));

        // Act + Assert
        assertThat(pendingItemRepository.findByUserIdAndTagsOverlapping(mine.getId(), new String[] {"shopping"}))
                .extracting(PendingItem::getId)
                .containsExactly(ofMine.getId());
    }

    @Test
    void deletingUser_cascadesToPendingItems() {
        // Arrange
        final User user = savedUser(1_030_007L, "cascade");
        pendingItemRepository.save(PendingItem.defer(user.getId(), "Buy milk", null, List.of(), null));

        // Act
        userRepository.deleteById(user.getId());
        userRepository.flush();

        // Assert — ON DELETE CASCADE removed the pending item
        assertThat(pendingItemRepository.findByUserIdOrderByDeferredAtDesc(user.getId())).isEmpty();
    }

    @Test
    void deletingOriginalList_nullsTheLink_butKeepsTheItem() {
        // Arrange
        final User user = savedUser(1_030_008L, "setnull");
        final TaskList list = savedListWithTags(user, "Groceries", List.of("shopping"));
        final PendingItem saved = pendingItemRepository.save(
                PendingItem.defer(user.getId(), "Buy milk", list.getId(), list.getTags(), null));

        // Act
        listRepository.deleteById(list.getId());
        listRepository.flush();

        // Assert — ON DELETE SET NULL cleared original_list_id; the pending item survives, tags retained
        final PendingItem reloaded = pendingItemRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getOriginalListId()).isEmpty();
        assertThat(reloaded.getOriginalListTags()).containsExactly("shopping");
    }

    @Test
    void findByUserIdOrderByDeferredAtDesc_returnsNewestFirst() {
        // Arrange
        final User user = savedUser(1_030_009L, "ordered");
        final PendingItem older =
                pendingItemRepository.save(PendingItem.defer(user.getId(), "Older", null, List.of(), null));
        final PendingItem newer =
                pendingItemRepository.save(PendingItem.defer(user.getId(), "Newer", null, List.of(), null));
        // Force a deterministic gap (both inserts default to now() at ms resolution otherwise)
        jdbcClient.sql("UPDATE pending_items SET deferred_at = now() - interval '1 hour' WHERE id = ?")
                .param(older.getId())
                .update();

        // Act
        final List<PendingItem> result = pendingItemRepository.findByUserIdOrderByDeferredAtDesc(user.getId());

        // Assert
        assertThat(result).extracting(PendingItem::getId).containsExactly(newer.getId(), older.getId());
    }
}
