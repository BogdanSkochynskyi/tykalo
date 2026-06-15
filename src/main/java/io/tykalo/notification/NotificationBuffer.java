package io.tykalo.notification;

import io.tykalo.list.ListActivityEvent;
import io.tykalo.user.ListChangeNotificationPreference;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Accumulates pending shared-list change notifications between the moment a change happens and the
 * moment its rollup is sent (TK-196). Two flavours of buffer live behind this seam:
 *
 * <ul>
 *   <li><b>Windowed</b> (INSTANT / BATCHED) — per (recipient, list, mode) bucket that flushes once at a
 *       fixed instant. The window starts at the first change and is not extended by later ones, so a
 *       burst of edits collapses into a single message.</li>
 *   <li><b>Daily</b> (DAILY_DIGEST) — per recipient, spanning all their shared lists, flushed once a day
 *       at the recipient's morning hour.</li>
 * </ul>
 *
 * <p>The interface is the testability seam: production uses a Redis-backed implementation, while the
 * aggregator's routing/formatting/quiet-hours logic is unit-tested against an in-memory double (no
 * Docker needed). Accumulation is additive — recording the same (actor, kind) twice sums the counts.
 */
public interface NotificationBuffer {

    /**
     * Records a windowed change for a recipient. {@code flushAt} is honoured only on the first record
     * for a (recipient, list, mode) bucket — it sets when the window closes; later records into the same
     * open bucket keep the original flush time.
     */
    void addToWindow(UUID recipientId, UUID listId, ListChangeNotificationPreference mode,
                     UUID actorId, ListActivityEvent.Kind kind, int count, Instant flushAt);

    /** Every windowed bucket whose flush time is at or before {@code now}. Does not remove them. */
    List<WindowFlush> dueWindows(Instant now);

    /** Pushes a bucket's flush time back (e.g. past quiet hours), keeping its accumulated changes. */
    void rescheduleWindow(UUID recipientId, UUID listId, ListChangeNotificationPreference mode, Instant newFlushAt);

    /** Discards a windowed bucket once it has been sent. */
    void removeWindow(UUID recipientId, UUID listId, ListChangeNotificationPreference mode);

    /** Records a change into a recipient's daily digest bucket, grouped by list. */
    void addToDaily(UUID recipientId, UUID listId, UUID actorId, ListActivityEvent.Kind kind, int count);

    /** A recipient's accumulated daily changes, grouped per list. Empty when nothing is buffered. */
    List<ListGroup> dailyFor(UUID recipientId);

    /** Discards a recipient's daily digest bucket once it has been sent. */
    void removeDaily(UUID recipientId);

    /** A single accumulated change line: an actor did something to {@code count} items. */
    record BufferedChange(UUID actorId, ListActivityEvent.Kind kind, int count) {
    }

    /** A windowed bucket ready to flush: the recipient, the list, the mode and the changes within it. */
    record WindowFlush(UUID recipientId, UUID listId, ListChangeNotificationPreference mode,
                       List<BufferedChange> changes) {
    }

    /** One list's worth of changes inside a daily digest. */
    record ListGroup(UUID listId, List<BufferedChange> changes) {
    }
}
