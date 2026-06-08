package io.tykalo.nudger;

/**
 * One row of {@code /nudgers list} (TK-154): an active nudger's {@code @username} and the
 * {@code karmaScore} they've earned acknowledging reminders. The username is resolved from the
 * pairing's {@code nudgerUserId}, which {@link Nudger} stores as a raw id.
 */
public record NudgerSummary(String username, int karmaScore) {
}
