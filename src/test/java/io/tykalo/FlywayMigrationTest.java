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
                "quiet_hours_start", "quiet_hours_end", "locale", "created_at", "digest_hour");
    }

    @Test
    void digestHour_defaultsTo8() {
        // Arrange
        insertUser(1006L);

        // Act
        final Integer digestHour = jdbcClient
                .sql("SELECT digest_hour FROM users WHERE tg_chat_id = 1006")
                .query(Integer.class)
                .single();

        // Assert
        assertThat(digestHour).isEqualTo(8);
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
        assertThat(versions).contains("1", "2", "3", "4", "5", "6", "7", "8");
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
                "status", "recurrence_rule", "gcal_event_id", "tags", "created_at", "updated_at", "archived_at",
                "nudgers_private");
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
    void reminderLogTable_hasExpectedColumns() {
        // Act
        final List<String> columns = columnsOf("reminder_log");

        // Assert
        assertThat(columns).containsExactlyInAnyOrder("id", "task_id", "level", "sent_at");
    }

    @Test
    void reminderLog_rejectsDuplicateTaskLevel() {
        // Arrange — a task to point the FK at
        final UUID ownerId = insertUser(6006L);
        final UUID listId = insertList(ownerId);
        final UUID taskId = UUID.randomUUID();
        jdbcClient.sql("INSERT INTO tasks (id, list_id, owner_id, title) VALUES (?, ?, ?, 'overdue')")
                .param(taskId).param(listId).param(ownerId)
                .update();
        insertReminderLog(taskId, 1);

        // Act / Assert — UNIQUE(task_id, level) backstop
        assertThatThrownBy(() -> insertReminderLog(taskId, 1))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void reminderLog_isIndexedOnTaskId() {
        // Act
        final List<String> indexes = jdbcClient
                .sql("SELECT indexname FROM pg_indexes WHERE tablename = 'reminder_log'")
                .query(String.class)
                .list();

        // Assert
        assertThat(indexes).contains("idx_reminder_log_task_id");
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

    @Test
    void nudgersTable_hasExpectedColumns() {
        // Act
        final List<String> columns = columnsOf("nudgers");

        // Assert
        assertThat(columns).containsExactlyInAnyOrder(
                "id", "owner_id", "nudger_user_id", "status", "karma_score", "added_at");
    }

    @Test
    void nudgers_defaultStatusPendingAndKarmaZero() {
        // Arrange
        final UUID ownerId = insertUser(7007L);
        final UUID nudgerUserId = insertUser(7008L);
        final UUID id = UUID.randomUUID();
        jdbcClient.sql("INSERT INTO nudgers (id, owner_id, nudger_user_id) VALUES (?, ?, ?)")
                .param(id).param(ownerId).param(nudgerUserId)
                .update();

        // Act
        final String status = jdbcClient.sql("SELECT status FROM nudgers WHERE id = ?")
                .param(id).query(String.class).single();
        final Integer karma = jdbcClient.sql("SELECT karma_score FROM nudgers WHERE id = ?")
                .param(id).query(Integer.class).single();

        // Assert
        assertThat(status).isEqualTo("PENDING");
        assertThat(karma).isZero();
    }

    @Test
    void nudgers_rejectInvalidStatus() {
        // Arrange
        final UUID ownerId = insertUser(7009L);
        final UUID nudgerUserId = insertUser(7010L);

        // Act / Assert
        assertThatThrownBy(() -> jdbcClient
                .sql("INSERT INTO nudgers (id, owner_id, nudger_user_id, status) VALUES (?, ?, ?, 'BOGUS')")
                .param(UUID.randomUUID()).param(ownerId).param(nudgerUserId)
                .update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void escalationPoliciesTable_hasExpectedColumns() {
        // Act
        final List<String> columns = columnsOf("escalation_policies");

        // Assert
        assertThat(columns).containsExactlyInAnyOrder(
                "id", "target_type", "target_id", "level", "delay_minutes", "reveal_fields");
    }

    @Test
    void escalationPolicies_rejectInvalidRevealField() {
        // Act / Assert
        assertThatThrownBy(() -> jdbcClient
                .sql("INSERT INTO escalation_policies (id, target_type, target_id, level, delay_minutes, reveal_fields) "
                        + "VALUES (?, 'TASK', ?, 1, 120, 'BOGUS')")
                .param(UUID.randomUUID()).param(UUID.randomUUID())
                .update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void nudgeLogTable_hasExpectedColumns() {
        // Act
        final List<String> columns = columnsOf("nudge_log");

        // Assert
        assertThat(columns).containsExactlyInAnyOrder(
                "id", "target_type", "target_id", "nudger_id", "level",
                "sent_at", "acknowledged_at", "message_template");
    }

    @Test
    void nudgeLog_rejectsMissingNudger() {
        // Act / Assert — FK to nudgers(id) is enforced
        assertThatThrownBy(() -> jdbcClient
                .sql("INSERT INTO nudge_log (id, target_type, target_id, nudger_id, level) "
                        + "VALUES (?, 'TASK', ?, ?, 1)")
                .param(UUID.randomUUID()).param(UUID.randomUUID()).param(UUID.randomUUID())
                .update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void nudgerTables_areIndexedOnExpectedColumns() {
        // Act
        final List<String> indexes = jdbcClient
                .sql("SELECT indexname FROM pg_indexes WHERE tablename IN ('nudgers', 'escalation_policies', 'nudge_log')")
                .query(String.class)
                .list();

        // Assert
        assertThat(indexes).contains(
                "idx_nudgers_owner_id", "idx_nudgers_nudger_user_id",
                "idx_escalation_policies_target", "idx_nudge_log_target", "idx_nudge_log_nudger_id");
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

    private void insertReminderLog(final UUID taskId, final int level) {
        jdbcClient.sql("INSERT INTO reminder_log (id, task_id, level, sent_at) VALUES (?, ?, ?, now())")
                .param(UUID.randomUUID())
                .param(taskId)
                .param(level)
                .update();
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
