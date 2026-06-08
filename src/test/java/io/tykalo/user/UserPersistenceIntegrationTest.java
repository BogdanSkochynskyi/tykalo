package io.tykalo.user;

import static org.assertj.core.api.Assertions.assertThat;

import io.tykalo.AbstractIntegrationTest;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Persists a {@link User} against the real Flyway-migrated schema, proving the entity
 * (and the {@code ZoneId} converter) map cleanly onto {@code V1__users_table.sql}.
 */
class UserPersistenceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Test
    void savesAndReadsBackUser_matchingTheSchema() {
        // Arrange
        final User saved = userRepository.save(
                User.create(555_001L, "alice", ZoneId.of("Europe/Kyiv"), "uk"));

        // Act
        final Optional<User> found = userRepository.findByTgChatId(555_001L);

        // Assert
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(found).isPresent().get().satisfies(user -> {
            assertThat(user.getId()).isEqualTo(saved.getId());
            assertThat(user.getTgUsername()).isEqualTo("alice");
            assertThat(user.getTimezone()).isEqualTo(ZoneId.of("Europe/Kyiv"));
            assertThat(user.getLocale()).isEqualTo("uk");
            assertThat(user.getQuietHoursStart()).isEqualTo(LocalTime.of(22, 0));
            assertThat(user.getQuietHoursEnd()).isEqualTo(LocalTime.of(7, 0));
            assertThat(user.getDigestHour()).isEqualTo(8);
            assertThat(user.getCreatedAt()).isNotNull();
        });
    }

    @Test
    void findByDigestHourIsNotNull_returnsOnlyDigestEnabledUsers() {
        // Arrange — one user keeps the default digest hour, one has it disabled
        final User enabled = userRepository.save(
                User.create(555_002L, "enabled", ZoneId.of("Europe/Kyiv"), "uk"));
        final User disabled = User.create(555_003L, "disabled", ZoneId.of("Europe/Kyiv"), "uk");
        disabled.setDigestHour(null);
        userRepository.save(disabled);

        // Act
        final var withDigest = userRepository.findByDigestHourIsNotNull();

        // Assert
        assertThat(withDigest).extracting(User::getId).contains(enabled.getId());
        assertThat(withDigest).extracting(User::getId).doesNotContain(disabled.getId());
    }
}
