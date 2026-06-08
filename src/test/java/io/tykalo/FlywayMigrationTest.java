package io.tykalo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

class FlywayMigrationTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void flyway_appliesV1Migration_successfully() {
        // Act
        final Boolean success = jdbcClient
                .sql("SELECT success FROM flyway_schema_history WHERE version = '1'")
                .query(Boolean.class)
                .single();

        // Assert
        assertThat(success).isTrue();
    }

    @Test
    void usersTable_hasExpectedColumns() {
        // Act
        final List<String> columns = jdbcClient
                .sql("SELECT column_name FROM information_schema.columns WHERE table_name = 'users'")
                .query(String.class)
                .list();

        // Assert
        assertThat(columns).containsExactlyInAnyOrder(
                "id", "tg_chat_id", "tg_username", "timezone",
                "quiet_hours_start", "quiet_hours_end", "locale", "created_at");
    }

    @Test
    void quietHours_defaultTo22To07() {
        // Arrange
        insertUser(1001L);

        // Act
        final LocalTime start = jdbcClient
                .sql("SELECT quiet_hours_start FROM users WHERE tg_chat_id = 1001")
                .query(LocalTime.class)
                .single();
        final LocalTime end = jdbcClient
                .sql("SELECT quiet_hours_end FROM users WHERE tg_chat_id = 1001")
                .query(LocalTime.class)
                .single();

        // Assert
        assertThat(start).isEqualTo(LocalTime.of(22, 0));
        assertThat(end).isEqualTo(LocalTime.of(7, 0));
    }

    @Test
    void tgChatId_rejectsDuplicates() {
        // Arrange
        insertUser(2002L);

        // Act / Assert
        assertThatThrownBy(() -> insertUser(2002L))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void tgChatId_isIndexed() {
        // Act
        final List<String> indexes = jdbcClient
                .sql("SELECT indexdef FROM pg_indexes WHERE tablename = 'users' AND indexdef LIKE '%tg_chat_id%'")
                .query(String.class)
                .list();

        // Assert
        assertThat(indexes).isNotEmpty();
    }

    @Test
    void flyway_appliesAllMigrations_successfully() {
        // Act
        final List<String> versions = jdbcClient
                .sql("SELECT version FROM flyway_schema_history WHERE success = true ORDER BY installed_rank")
                .query(String.class)
                .list();

        // Assert
        assertThat(versions).contains("1", "2", "3", "4", "5");
    }

    @Test
    void listsTable_hasExpectedColumns() {
        // Act
        final List<String> columns = columnsOf("lists");

        // Assert
        assertThat(columns).containsExactlyInAnyOrder(
                "id", "owner_id", "name", "type", "recurrence_rule",
                "nudger_default_policy", "created_at", "archived_at");
    }

    @Test
    void tasksTable_hasExpectedColumns() {
        // Act
        final List<String> columns = columnsOf("tasks");

        // Assert
        assertThat(columns).containsExactlyInAnyOrder(
                "id", "list_id", "owner_id", "title", "description", "due_at", "priority",
                "status", "recurrence_rule", "gcal_event_id", "tags", "created_at", "updated_at", "archived_at");
    }

    @Test
    void listMessagesTable_hasExpectedColumns() {
        // Act
        final List<String> columns = columnsOf("list_messages");

        // Assert
        assertThat(columns).containsExactlyInAnyOrder(
                "id", "list_id", "tg_chat_id", "tg_message_id", "last_rendered_at");
    }

    @Test
    void lists_rejectInvalidType() {
        // Arrange
        final UUID ownerId = insertUser(3003L);

        // Act / Assert
        assertThatThrownBy(() -> jdbcClient
                .sql("INSERT INTO lists (id, owner_id, name, type, nudger_default_policy) VALUES (?, ?, 'L', 'BOGUS', 'OFF')")
                .param(UUID.randomUUID())
                .param(ownerId)
                .update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void lists_rejectMissingOwner() {
        // Act / Assert — FK to users(id) is enforced
        assertThatThrownBy(() -> jdbcClient
                .sql("INSERT INTO lists (id, owner_id, name, type, nudger_default_policy) VALUES (?, ?, 'L', 'INBOX', 'OFF')")
                .param(UUID.randomUUID())
                .param(UUID.randomUUID())
                .update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void tasks_defaultStatusIsTodo_andTagsEmpty() {
        // Arrange
        final UUID ownerId = insertUser(4004L);
        final UUID listId = insertList(ownerId);
        final UUID taskId = UUID.randomUUID();
        jdbcClient.sql("INSERT INTO tasks (id, list_id, owner_id, title) VALUES (?, ?, ?, 'Buy milk')")
                .param(taskId).param(listId).param(ownerId)
                .update();

        // Act
        final String status = jdbcClient.sql("SELECT status FROM tasks WHERE id = ?")
                .param(taskId).query(String.class).single();
        final Integer tagCount = jdbcClient.sql("SELECT cardinality(tags) FROM tasks WHERE id = ?")
                .param(taskId).query(Integer.class).single();

        // Assert
        assertThat(status).isEqualTo("TODO");
        assertThat(tagCount).isZero();
    }

    @Test
    void tasks_rejectMissingList() {
        // Arrange
        final UUID ownerId = insertUser(5005L);

        // Act / Assert — FK to lists(id) is enforced
        assertThatThrownBy(() -> jdbcClient
                .sql("INSERT INTO tasks (id, list_id, owner_id, title) VALUES (?, ?, ?, 'orphan')")
                .param(UUID.randomUUID())
                .param(UUID.randomUUID())
                .param(ownerId)
                .update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void tasks_areIndexedOnOwnerDueAtAndListId() {
        // Act
        final List<String> indexes = jdbcClient
                .sql("SELECT indexname FROM pg_indexes WHERE tablename = 'tasks'")
                .query(String.class)
                .list();

        // Assert
        assertThat(indexes).contains("idx_tasks_owner_due_at", "idx_tasks_list_id");
    }

    @Test
    void listMessages_isIndexedOnListIdAndChatId() {
        // Act
        final List<String> indexes = jdbcClient
                .sql("SELECT indexname FROM pg_indexes WHERE tablename = 'list_messages'")
                .query(String.class)
                .list();

        // Assert
        assertThat(indexes).contains("idx_list_messages_list_id_chat_id");
    }

    private List<String> columnsOf(final String table) {
        return jdbcClient
                .sql("SELECT column_name FROM information_schema.columns WHERE table_name = ?")
                .param(table)
                .query(String.class)
                .list();
    }

    private UUID insertUser(final long tgChatId) {
        final UUID id = UUID.randomUUID();
        jdbcClient.sql("INSERT INTO users (id, tg_chat_id) VALUES (?, ?)")
                .param(id)
                .param(tgChatId)
                .update();
        return id;
    }

    private UUID insertList(final UUID ownerId) {
        final UUID id = UUID.randomUUID();
        jdbcClient.sql("INSERT INTO lists (id, owner_id, name, type, nudger_default_policy) "
                        + "VALUES (?, ?, 'Inbox', 'INBOX', 'OFF')")
                .param(id)
                .param(ownerId)
                .update();
        return id;
    }
}
