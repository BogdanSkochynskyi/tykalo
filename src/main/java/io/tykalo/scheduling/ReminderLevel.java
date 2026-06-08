package io.tykalo.scheduling;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * The escalating tiers of an overdue-task reminder, each a fixed offset past {@code due_at}: level 1
 * fires +2h after the deadline, level 2 +6h, level 3 +12h. The {@code level} is the small integer
 * persisted in {@code reminder_log}; the {@link #offset()} is how long after the due date the tier
 * becomes eligible.
 *
 * <p>{@link #maxElapsed(Instant, Instant)} returns the highest tier whose offset has already passed
 * at a given instant — the reminder the cron should consider sending now. Returning only the top
 * eligible tier (rather than every passed one) is what makes a post-downtime sweep send a single,
 * most-advanced reminder instead of a burst.
 */
public enum ReminderLevel {

    L1(1, Duration.ofHours(2)),
    L2(2, Duration.ofHours(6)),
    L3(3, Duration.ofHours(12));

    private final int level;
    private final Duration offset;

    ReminderLevel(final int level, final Duration offset) {
        this.level = level;
        this.offset = offset;
    }

    public int level() {
        return level;
    }

    public Duration offset() {
        return offset;
    }

    /** Whether this tier's offset past {@code dueAt} has been reached by {@code now}. */
    public boolean hasElapsed(final Instant dueAt, final Instant now) {
        return !now.isBefore(dueAt.plus(offset));
    }

    /**
     * The highest tier whose offset past {@code dueAt} has elapsed by {@code now}, or empty when not
     * even the first tier is due yet. Tiers are declared ascending, so the last matching one is the
     * most advanced.
     */
    public static Optional<ReminderLevel> maxElapsed(final Instant dueAt, final Instant now) {
        ReminderLevel max = null;
        for (final ReminderLevel candidate : values()) {
            if (candidate.hasElapsed(dueAt, now)) {
                max = candidate;
            }
        }
        return Optional.ofNullable(max);
    }
}
