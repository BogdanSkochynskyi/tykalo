package io.tykalo.list;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Per-user "current list" context, stored in Redis under {@code user:{userId}:currentList} with a
 * 24-hour TTL. The current list is where an unqualified {@code /add} drops a task; when none is set
 * (or the stored one was archived) it falls back to the user's Inbox.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CurrentContextService {

    static final Duration TTL = Duration.ofHours(24);

    private final StringRedisTemplate redis;
    private final ListService listService;

    public void set(final UUID userId, final UUID listId) {
        redis.opsForValue().set(key(userId), listId.toString(), TTL);
        log.debug("Set current list for user {} to {}", userId, listId);
    }

    public Optional<UUID> get(final UUID userId) {
        final String value = redis.opsForValue().get(key(userId));
        return Optional.ofNullable(value).map(UUID::fromString);
    }

    public void clear(final UUID userId) {
        redis.delete(key(userId));
    }

    /**
     * Resolves the list an unqualified {@code /add} should target: the stored current list if it is
     * still active, otherwise the Inbox. A stored id whose list has since been archived is treated
     * as stale — the key is cleared and the Inbox is used instead.
     */
    public Optional<TaskList> resolveCurrentList(final UUID userId) {
        final Optional<UUID> currentId = get(userId);
        if (currentId.isPresent()) {
            final Optional<TaskList> current = listService.getActiveById(currentId.get());
            if (current.isPresent()) {
                return current;
            }
            log.debug("Clearing stale current list {} for user {}", currentId.get(), userId);
            clear(userId);
        }
        return listService.findInbox(userId);
    }

    private String key(final UUID userId) {
        return "user:" + userId + ":currentList";
    }
}
