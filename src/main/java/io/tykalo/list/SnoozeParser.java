package io.tykalo.list;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Parses the {@code <duration>} argument of {@code /snooze} into the new absolute deadline. Unlike
 * {@link DueDateParser} (which reads a leading prefix off a longer title), the whole argument here
 * is the duration, so a match must be total. Resolution is deterministic and timezone-aware.
 *
 * <p>Supported forms (case-insensitive), first match wins:
 * <ul>
 *   <li>compact relative — {@code 1h}, {@code 30m}, {@code 2d}, {@code 1w} (and spaced / long unit
 *       spellings like {@code 2 hours}, {@code 3 days}) → {@code now + duration};</li>
 *   <li>{@code tomorrow} → the next day at {@value DueDateParser#DEFAULT_HOUR}:00 local;</li>
 *   <li>{@code next week} → the coming Monday at {@value DueDateParser#DEFAULT_HOUR}:00 local.</li>
 * </ul>
 *
 * <p>A non-positive amount or an unrecognized string yields {@link Optional#empty()}, so the caller
 * can answer with usage guidance rather than snoozing to a meaningless instant.
 */
@Service
public class SnoozeParser {

    private static final LocalTime DEFAULT_TIME = LocalTime.of(DueDateParser.DEFAULT_HOUR, 0);

    private static final Pattern COMPACT = Pattern.compile(
            "^(\\d+)\\s*(m|min|mins|minute|minutes|h|hr|hrs|hour|hours|d|day|days|w|wk|wks|week|weeks)$",
            Pattern.CASE_INSENSITIVE);

    /**
     * Resolves {@code text} to the snooze target instant, relative to {@code now} in {@code zone};
     * empty when the text is not a recognized, positive duration.
     */
    public Optional<Instant> parse(final String text, final ZoneId zone, final Instant now) {
        final String input = text == null ? "" : text.strip();
        if (input.isEmpty()) {
            return Optional.empty();
        }
        final ZonedDateTime reference = now.atZone(zone);
        final String lower = input.toLowerCase(Locale.ROOT);

        if ("tomorrow".equals(lower)) {
            return Optional.of(reference.toLocalDate().plusDays(1).atTime(DEFAULT_TIME).atZone(zone).toInstant());
        }
        if ("next week".equals(lower)) {
            final LocalDate nextMonday = reference.toLocalDate()
                    .with(TemporalAdjusters.next(DayOfWeek.MONDAY));
            return Optional.of(nextMonday.atTime(DEFAULT_TIME).atZone(zone).toInstant());
        }

        final Matcher compact = COMPACT.matcher(input);
        if (compact.matches()) {
            final long amount = Long.parseLong(compact.group(1));
            if (amount <= 0) {
                return Optional.empty();
            }
            return Optional.of(reference.plus(durationOf(amount, compact.group(2))).toInstant());
        }
        return Optional.empty();
    }

    private static Duration durationOf(final long amount, final String unit) {
        final String u = unit.toLowerCase(Locale.ROOT);
        if (u.startsWith("m")) {
            return Duration.ofMinutes(amount);
        }
        if (u.startsWith("h")) {
            return Duration.ofHours(amount);
        }
        if (u.startsWith("d")) {
            return Duration.ofDays(amount);
        }
        return Duration.ofDays(amount * 7);
    }
}
