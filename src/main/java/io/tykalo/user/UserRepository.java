package io.tykalo.user;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByTgChatId(Long tgChatId);

    /** Looks a user up by Telegram username, case-insensitively (handles have no canonical case). */
    Optional<User> findByTgUsernameIgnoreCase(String tgUsername);

    /** Users with the morning digest enabled (a non-null digest hour) — the cron's candidate set. */
    List<User> findByDigestHourIsNotNull();
}
