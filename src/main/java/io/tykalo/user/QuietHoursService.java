package io.tykalo.user;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import org.springframework.stereotype.Service;

/**
 * Decides whether a given instant falls inside a user's quiet hours — the window during which the
 * bot must not send messages. Quiet hours are stored as a pair of {@link LocalTime} bounds in the
 * user's own timezone (the instant is converted into that zone before the check, falling back to
 * UTC when the zone is unset).
 *
 * <p>The window is half-open {@code [start, end)}: the start time is quiet, the end time is not.
 * A window may cross midnight (e.g. {@code 22:00–07:00}). Quiet hours count as disabled when either
 * bound is {@code null} or the two bounds are equal (an empty window).
 */
@Service
public class QuietHoursService {

    private static final ZoneId UTC = ZoneId.of("UTC");

    public boolean isQuiet(final User user, final Instant instant) {
        final LocalTime start = user.getQuietHoursStart();
        final LocalTime end = user.getQuietHoursEnd();
        if (start == null || end == null || start.equals(end)) {
            return false;
        }
        final ZoneId zone = user.getTimezone() == null ? UTC : user.getTimezone();
        final LocalTime now = instant.atZone(zone).toLocalTime();
        if (start.isBefore(end)) {
            return !now.isBefore(start) && now.isBefore(end);
        }
        return !now.isBefore(start) || now.isBefore(end);
    }
}
