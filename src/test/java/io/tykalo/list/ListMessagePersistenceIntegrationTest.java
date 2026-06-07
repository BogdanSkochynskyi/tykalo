package io.tykalo.list;

import static org.assertj.core.api.Assertions.assertThat;

import io.tykalo.AbstractIntegrationTest;
import io.tykalo.user.User;
import io.tykalo.user.UserRepository;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Persists a {@link ListMessage} against the real Flyway-migrated schema, proving the entity maps
 * cleanly onto {@code V4__list_messages_table.sql} and that
 * {@link ListMessageRepository#findByListIdAndTgChatId} behaves against real SQL.
 *
 * <p>Owns the {@code 930_00x} tg_chat_id range — the singleton Postgres is shared across integration
 * classes and {@code users.tg_chat_id} is UNIQUE.
 */
class ListMessagePersistenceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ListMessageRepository listMessageRepository;

    @Autowired
    private ListRepository listRepository;

    @Autowired
    private UserRepository userRepository;

    private TaskList savedList(final long tgChatId) {
        final User owner = userRepository.save(User.create(tgChatId, "owner", ZoneId.of("Europe/Kyiv"), "uk"));
        return listRepository.save(TaskList.checklist(owner, "Groceries"));
    }

    @Test
    void savesAndReadsBackByListAndChat() {
        // Arrange
        final TaskList list = savedList(930_001L);
        final long chatId = 930_001L;

        // Act
        final ListMessage saved = listMessageRepository.save(ListMessage.of(list.getId(), chatId, 4242L));
        final Optional<ListMessage> found = listMessageRepository.findByListIdAndTgChatId(list.getId(), chatId);

        // Assert
        assertThat(saved.getId()).isNotNull();
        assertThat(found).isPresent().get().satisfies(m -> {
            assertThat(m.getListId()).isEqualTo(list.getId());
            assertThat(m.getTgChatId()).isEqualTo(chatId);
            assertThat(m.getTgMessageId()).isEqualTo(4242L);
            assertThat(m.getLastRenderedAt()).isNotNull();
        });
    }

    @Test
    void findByListAndChat_isEmpty_forADifferentChat() {
        // Arrange
        final TaskList list = savedList(930_002L);
        listMessageRepository.save(ListMessage.of(list.getId(), 930_002L, 11L));

        // Act
        final Optional<ListMessage> other = listMessageRepository.findByListIdAndTgChatId(list.getId(), 930_999L);

        // Assert
        assertThat(other).isEmpty();
    }

    @Test
    void refreshedTo_updatesMessageIdAndPersists() {
        // Arrange
        final TaskList list = savedList(930_003L);
        final ListMessage message = listMessageRepository.save(ListMessage.of(list.getId(), 930_003L, 100L));

        // Act
        message.refreshedTo(200L);
        listMessageRepository.save(message);

        // Assert
        assertThat(listMessageRepository.findByListIdAndTgChatId(list.getId(), 930_003L))
                .get().extracting(ListMessage::getTgMessageId).isEqualTo(200L);
    }
}
