package io.tykalo.notification;

import static org.assertj.core.api.Assertions.assertThat;

import io.tykalo.AbstractIntegrationTest;
import io.tykalo.list.ListActivityEvent;
import io.tykalo.notification.NotificationBuffer.BufferedChange;
import io.tykalo.notification.NotificationBuffer.ListGroup;
import io.tykalo.notification.NotificationBuffer.WindowFlush;
import io.tykalo.user.ListChangeNotificationPreference;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Verifies the Redis-backed {@link NotificationBuffer} against a real Redis (Testcontainers, CI). Uses
 * random recipient/list UUIDs so keys never collide with other integration-test classes sharing the
 * singleton container, and filters {@link NotificationBuffer#dueWindows} by recipient for the same reason.
 */
class RedisNotificationBufferTest extends AbstractIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-06-15T12:00:00Z");

    @Autowired
    private RedisNotificationBuffer buffer;

    private WindowFlush windowFor(final UUID recipientId, final Instant now) {
        return buffer.dueWindows(now).stream()
                .filter(flush -> flush.recipientId().equals(recipientId))
                .findFirst()
                .orElse(null);
    }

    @Test
    void addToWindow_accumulatesChanges_andSurfacesWhenDue() {
        final UUID recipient = UUID.randomUUID();
        final UUID list = UUID.randomUUID();
        final UUID actor = UUID.randomUUID();
        buffer.addToWindow(recipient, list, ListChangeNotificationPreference.BATCHED,
                actor, ListActivityEvent.Kind.ADDED, 2, T0);
        buffer.addToWindow(recipient, list, ListChangeNotificationPreference.BATCHED,
                actor, ListActivityEvent.Kind.ADDED, 3, T0.plus(1, ChronoUnit.MINUTES));

        assertThat(windowFor(recipient, T0.minusSeconds(1))).isNull();
        final WindowFlush flush = windowFor(recipient, T0.plusSeconds(1));
        assertThat(flush).isNotNull();
        assertThat(flush.mode()).isEqualTo(ListChangeNotificationPreference.BATCHED);
        assertThat(flush.changes()).containsExactly(new BufferedChange(actor, ListActivityEvent.Kind.ADDED, 5));

        buffer.removeWindow(recipient, list, ListChangeNotificationPreference.BATCHED);
        assertThat(windowFor(recipient, T0.plusSeconds(1))).isNull();
    }

    @Test
    void rescheduleWindow_movesFlushTime() {
        final UUID recipient = UUID.randomUUID();
        final UUID list = UUID.randomUUID();
        buffer.addToWindow(recipient, list, ListChangeNotificationPreference.INSTANT,
                UUID.randomUUID(), ListActivityEvent.Kind.COMPLETED, 1, T0);

        buffer.rescheduleWindow(recipient, list, ListChangeNotificationPreference.INSTANT,
                T0.plus(1, ChronoUnit.HOURS));

        assertThat(windowFor(recipient, T0.plusSeconds(1))).isNull();
        assertThat(windowFor(recipient, T0.plus(2, ChronoUnit.HOURS))).isNotNull();
        buffer.removeWindow(recipient, list, ListChangeNotificationPreference.INSTANT);
    }

    @Test
    void daily_accumulatesPerList_andClears() {
        final UUID recipient = UUID.randomUUID();
        final UUID groceries = UUID.randomUUID();
        final UUID chores = UUID.randomUUID();
        final UUID anna = UUID.randomUUID();
        buffer.addToDaily(recipient, groceries, anna, ListActivityEvent.Kind.ADDED, 4);
        buffer.addToDaily(recipient, groceries, anna, ListActivityEvent.Kind.ADDED, 1);
        buffer.addToDaily(recipient, chores, anna, ListActivityEvent.Kind.COMPLETED, 2);

        final List<ListGroup> groups = buffer.dailyFor(recipient);

        assertThat(groups).hasSize(2);
        assertThat(groups).anySatisfy(group -> {
            assertThat(group.listId()).isEqualTo(groceries);
            assertThat(group.changes()).containsExactly(new BufferedChange(anna, ListActivityEvent.Kind.ADDED, 5));
        });
        buffer.removeDaily(recipient);
        assertThat(buffer.dailyFor(recipient)).isEmpty();
    }
}
