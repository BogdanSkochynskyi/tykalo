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

    private void insertUser(final long tgChatId) {
        jdbcClient.sql("INSERT INTO users (id, tg_chat_id) VALUES (?, ?)")
                .param(UUID.randomUUID())
                .param(tgChatId)
                .update();
    }
}
