package io.tykalo.notification;

import io.tykalo.list.ListActivityEvent;
import io.tykalo.user.ListChangeNotificationPreference;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * In-memory {@link NotificationBuffer} for unit tests — mirrors the Redis implementation's semantics
 * (additive accumulation, window start fixed at the first change) without needing Docker. Lets the
 * {@link ListChangeAggregator} routing/flush/quiet-hours logic be exercised in plain Mockito tests.
 */
class InMemoryNotificationBuffer implements NotificationBuffer {

    private final Map<String, WindowBucket> windows = new LinkedHashMap<>();
    private final Map<UUID, Map<String, Integer>> daily = new LinkedHashMap<>();

    @Override
    public void addToWindow(final UUID recipientId, final UUID listId, final ListChangeNotificationPreference mode,
                            final UUID actorId, final ListActivityEvent.Kind kind, final int count,
                            final Instant flushAt) {
        final WindowBucket bucket = windows.computeIfAbsent(
                windowKey(recipientId, listId, mode), ignored -> new WindowBucket(recipientId, listId, mode, flushAt));
        bucket.counts.merge(actorId + ":" + kind.name(), count, Integer::sum);
    }

    @Override
    public List<WindowFlush> dueWindows(final Instant now) {
        final List<WindowFlush> flushes = new ArrayList<>();
        for (final WindowBucket bucket : windows.values()) {
            if (!bucket.flushAt.isAfter(now)) {
                flushes.add(new WindowFlush(bucket.recipientId, bucket.listId, bucket.mode, changes(bucket.counts)));
            }
        }
        return flushes;
    }

    @Override
    public void rescheduleWindow(final UUID recipientId, final UUID listId,
                                 final ListChangeNotificationPreference mode, final Instant newFlushAt) {
        final WindowBucket bucket = windows.get(windowKey(recipientId, listId, mode));
        if (bucket != null) {
            bucket.flushAt = newFlushAt;
        }
    }

    @Override
    public void removeWindow(final UUID recipientId, final UUID listId, final ListChangeNotificationPreference mode) {
        windows.remove(windowKey(recipientId, listId, mode));
    }

    @Override
    public void addToDaily(final UUID recipientId, final UUID listId, final UUID actorId,
                           final ListActivityEvent.Kind kind, final int count) {
        daily.computeIfAbsent(recipientId, ignored -> new LinkedHashMap<>())
                .merge(listId + ":" + actorId + ":" + kind.name(), count, Integer::sum);
    }

    @Override
    public List<ListGroup> dailyFor(final UUID recipientId) {
        final Map<String, Integer> fields = daily.get(recipientId);
        if (fields == null) {
            return List.of();
        }
        final Map<UUID, List<BufferedChange>> byList = new LinkedHashMap<>();
        fields.forEach((field, count) -> {
            final String[] parts = field.split(":");
            byList.computeIfAbsent(UUID.fromString(parts[0]), ignored -> new ArrayList<>())
                    .add(new BufferedChange(UUID.fromString(parts[1]), ListActivityEvent.Kind.valueOf(parts[2]), count));
        });
        return byList.entrySet().stream().map(e -> new ListGroup(e.getKey(), e.getValue())).toList();
    }

    @Override
    public void removeDaily(final UUID recipientId) {
        daily.remove(recipientId);
    }

    boolean hasWindow(final UUID recipientId, final UUID listId, final ListChangeNotificationPreference mode) {
        return windows.containsKey(windowKey(recipientId, listId, mode));
    }

    private List<BufferedChange> changes(final Map<String, Integer> counts) {
        final List<BufferedChange> changes = new ArrayList<>();
        counts.forEach((field, count) -> {
            final String[] parts = field.split(":");
            changes.add(new BufferedChange(UUID.fromString(parts[0]), ListActivityEvent.Kind.valueOf(parts[1]), count));
        });
        return changes;
    }

    private String windowKey(final UUID recipientId, final UUID listId, final ListChangeNotificationPreference mode) {
        return recipientId + "|" + listId + "|" + mode.name();
    }

    private static final class WindowBucket {
        private final UUID recipientId;
        private final UUID listId;
        private final ListChangeNotificationPreference mode;
        private Instant flushAt;
        private final Map<String, Integer> counts = new LinkedHashMap<>();

        private WindowBucket(final UUID recipientId, final UUID listId,
                             final ListChangeNotificationPreference mode, final Instant flushAt) {
            this.recipientId = recipientId;
            this.listId = listId;
            this.mode = mode;
            this.flushAt = flushAt;
        }
    }
}
