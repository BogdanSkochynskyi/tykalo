package io.tykalo.user;

/**
 * How a user wants to be pushed about changes to lists they share with others (TK-196). This governs
 * <em>push notifications only</em>; the live, self-updating list message (TK-195) syncs regardless of
 * this setting — it is UI sync, not a notification. A user is never notified of their own changes, and
 * quiet hours ({@code QuietHoursService}) are always respected.
 */
public enum ListChangeNotificationPreference {

    /**
     * Push on each change, coalescing one actor's rapid-fire edits within a 30-second window into a
     * single message ("@anna added 3 items to Groceries").
     */
    INSTANT,

    /**
     * The default. Buffer changes in a 10-minute window per (user, list) and flush one rollup at the
     * window's end ("Changes in 'Groceries' (last 10 min): @anna added 4 items, @petro completed 2").
     */
    BATCHED,

    /** Roll every shared-list change up into one summary sent once a day at the user's morning hour. */
    DAILY_DIGEST,

    /** No push at all — only the live list message updates. */
    OFF
}
