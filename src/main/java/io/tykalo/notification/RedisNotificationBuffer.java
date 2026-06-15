package io.tykalo.notification;

import io.tykalo.list.ListActivityEvent;
import io.tykalo.user.ListChangeNotificationPreference;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis-backed {@link NotificationBuffer} (TK-196). Each windowed bucket is a Redis hash whose change
 * fields ({@code "{actorId}:{KIND}"} → count) are accumulated with atomic {@code HINCRBY}, plus a few
 * {@code __}-prefixed metadata fields; a sorted set ({@link #WINDOW_INDEX}) indexes the buckets by flush
 * time so the sweep can pull only those that are due. The window start is fixed by {@code ZADD NX}
 * ({@code addIfAbsent}) so a burst of changes does not keep pushing the flush time out. Daily buckets
 * are a single hash per recipient ({@code "{listId}:{actorId}:{KIND}"} → count).
 *
 * <p>The accumulation is atomic per field; the index add is best-effort and idempotent. On a single
 * instance (this bot's deployment) that is sufficient — there is no cross-bucket invariant to protect.
 */
@Component
@RequiredArgsConstructor
public class RedisNotificationBuffer implements NotificationBuffer {

    private static final String WINDOW_PREFIX = "listnotif:w:";
    private static final String WINDOW_INDEX = "listnotif:w:index";
    private static final String DAILY_PREFIX = "listnotif:d:";
    private static final String META_RECIPIENT = "__r";
    private static final String META_LIST = "__l";
    private static final String META_MODE = "__m";

    private final StringRedisTemplate redis;

    @Override
    public void addToWindow(final UUID recipientId, final UUID listId, final ListChangeNotificationPreference mode,
                            final UUID actorId, final ListActivityEvent.Kind kind, final int count,
                            final Instant flushAt) {
        final String key = windowKey(recipientId, listId, mode);
        redis.opsForHash().putIfAbsent(key, META_RECIPIENT, recipientId.toString());
        redis.opsForHash().putIfAbsent(key, META_LIST, listId.toString());
        redis.opsForHash().putIfAbsent(key, META_MODE, mode.name());
        redis.opsForHash().increment(key, changeField(actorId, kind), count);
        redis.opsForZSet().addIfAbsent(WINDOW_INDEX, key, flushAt.toEpochMilli());
    }

    @Override
    public List<WindowFlush> dueWindows(final Instant now) {
        final Set<String> keys = redis.opsForZSet().rangeByScore(WINDOW_INDEX, 0, now.toEpochMilli());
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }
        final List<WindowFlush> flushes = new ArrayList<>();
        for (final String key : keys) {
            final Map<Object, Object> hash = redis.opsForHash().entries(key);
            if (hash.isEmpty()) {
                redis.opsForZSet().remove(WINDOW_INDEX, key);
                continue;
            }
            flushes.add(toWindowFlush(hash));
        }
        return flushes;
    }

    @Override
    public void rescheduleWindow(final UUID recipientId, final UUID listId,
                                 final ListChangeNotificationPreference mode, final Instant newFlushAt) {
        redis.opsForZSet().add(WINDOW_INDEX, windowKey(recipientId, listId, mode), newFlushAt.toEpochMilli());
    }

    @Override
    public void removeWindow(final UUID recipientId, final UUID listId, final ListChangeNotificationPreference mode) {
        final String key = windowKey(recipientId, listId, mode);
        redis.delete(key);
        redis.opsForZSet().remove(WINDOW_INDEX, key);
    }

    @Override
    public void addToDaily(final UUID recipientId, final UUID listId, final UUID actorId,
                           final ListActivityEvent.Kind kind, final int count) {
        redis.opsForHash().increment(dailyKey(recipientId), dailyField(listId, actorId, kind), count);
    }

    @Override
    public List<ListGroup> dailyFor(final UUID recipientId) {
        final Map<Object, Object> hash = redis.opsForHash().entries(dailyKey(recipientId));
        if (hash.isEmpty()) {
            return List.of();
        }
        final Map<UUID, List<BufferedChange>> byList = new LinkedHashMap<>();
        for (final Map.Entry<Object, Object> entry : hash.entrySet()) {
            final String[] parts = entry.getKey().toString().split(":");
            final UUID listId = UUID.fromString(parts[0]);
            final BufferedChange change = new BufferedChange(
                    UUID.fromString(parts[1]), ListActivityEvent.Kind.valueOf(parts[2]),
                    Integer.parseInt(entry.getValue().toString()));
            byList.computeIfAbsent(listId, ignored -> new ArrayList<>()).add(change);
        }
        return byList.entrySet().stream()
                .map(e -> new ListGroup(e.getKey(), List.copyOf(e.getValue())))
                .toList();
    }

    @Override
    public void removeDaily(final UUID recipientId) {
        redis.delete(dailyKey(recipientId));
    }

    private WindowFlush toWindowFlush(final Map<Object, Object> hash) {
        final UUID recipientId = UUID.fromString(hash.get(META_RECIPIENT).toString());
        final UUID listId = UUID.fromString(hash.get(META_LIST).toString());
        final ListChangeNotificationPreference mode =
                ListChangeNotificationPreference.valueOf(hash.get(META_MODE).toString());
        final List<BufferedChange> changes = new ArrayList<>();
        for (final Map.Entry<Object, Object> entry : hash.entrySet()) {
            final String field = entry.getKey().toString();
            if (field.startsWith("__")) {
                continue;
            }
            final String[] parts = field.split(":");
            changes.add(new BufferedChange(UUID.fromString(parts[0]), ListActivityEvent.Kind.valueOf(parts[1]),
                    Integer.parseInt(entry.getValue().toString())));
        }
        return new WindowFlush(recipientId, listId, mode, List.copyOf(changes));
    }

    private String windowKey(final UUID recipientId, final UUID listId,
                             final ListChangeNotificationPreference mode) {
        return WINDOW_PREFIX + mode.name() + ":" + recipientId + ":" + listId;
    }

    private String dailyKey(final UUID recipientId) {
        return DAILY_PREFIX + recipientId;
    }

    private String changeField(final UUID actorId, final ListActivityEvent.Kind kind) {
        return actorId + ":" + kind.name();
    }

    private String dailyField(final UUID listId, final UUID actorId, final ListActivityEvent.Kind kind) {
        return listId + ":" + actorId + ":" + kind.name();
    }
}
