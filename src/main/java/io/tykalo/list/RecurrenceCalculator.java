package io.tykalo.list;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Computes the next occurrence of a recurring task from the short {@code FREQ=…} rule produced by
 * {@link RecurrenceParser} (the full RFC&nbsp;5545 grammar lands in TK-201). The anchor is the
 * previous {@code dueAt}; the calculation runs in the owner's {@code zone} via {@link ZonedDateTime}
 * so the wall-clock time-of-day is preserved across day boundaries and DST shifts.
 *
 * <p>Supported rules:
 * <ul>
 *   <li>{@code FREQ=DAILY} → anchor + 1 day</li>
 *   <li>{@code FREQ=WEEKLY} (no {@code BYDAY}) → anchor + 1 week</li>
 *   <li>{@code FREQ=WEEKLY;BYDAY=…} (weekdays / weekends / a single day) → the first day strictly
 *       after the anchor whose day-of-week is in the {@code BYDAY} set</li>
 * </ul>
 *
 * <p>Anything else (an unknown {@code FREQ} such as a future {@code MONTHLY}, a malformed rule, or a
 * {@code null} input) yields {@link Optional#empty()} — the caller then simply does not expand, which
 * is the safe default.
 */
@Service
public class RecurrenceCalculator {

    /** First occurrence strictly after {@code anchor} per {@code rule}, or empty when uncomputable. */
    public Optional<Instant> nextOccurrence(final @Nullable String rule, final @Nullable Instant anchor,
                                            final ZoneId zone) {
        if (rule == null || rule.isBlank() || anchor == null) {
            return Optional.empty();
        }
        final ZonedDateTime current = anchor.atZone(zone);
        final String freq = field(rule, "FREQ=");
        if (freq == null) {
            return Optional.empty();
        }
        return switch (freq) {
            case "DAILY" -> Optional.of(current.plusDays(1).toInstant());
            case "WEEKLY" -> nextWeekly(rule, current);
            default -> Optional.empty();
        };
    }

    private Optional<Instant> nextWeekly(final String rule, final ZonedDateTime current) {
        final String byDay = field(rule, "BYDAY=");
        if (byDay == null) {
            return Optional.of(current.plusWeeks(1).toInstant());
        }
        final Set<DayOfWeek> days = parseByDay(byDay);
        if (days.isEmpty()) {
            return Optional.empty();
        }
        ZonedDateTime candidate = current.plusDays(1);
        for (int i = 0; i < 7; i++) {
            if (days.contains(candidate.getDayOfWeek())) {
                return Optional.of(candidate.toInstant());
            }
            candidate = candidate.plusDays(1);
        }
        return Optional.empty();
    }

    private static Set<DayOfWeek> parseByDay(final String byDay) {
        final Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        for (final String code : byDay.split(",")) {
            final DayOfWeek day = toDayOfWeek(code.strip().toUpperCase(Locale.ROOT));
            if (day != null) {
                days.add(day);
            }
        }
        return days;
    }

    private static @Nullable DayOfWeek toDayOfWeek(final String code) {
        return switch (code) {
            case "MO" -> DayOfWeek.MONDAY;
            case "TU" -> DayOfWeek.TUESDAY;
            case "WE" -> DayOfWeek.WEDNESDAY;
            case "TH" -> DayOfWeek.THURSDAY;
            case "FR" -> DayOfWeek.FRIDAY;
            case "SA" -> DayOfWeek.SATURDAY;
            case "SU" -> DayOfWeek.SUNDAY;
            default -> null;
        };
    }

    /** Extracts the value of a {@code KEY=value} segment from a {@code ;}-separated rule. */
    private static @Nullable String field(final String rule, final String key) {
        for (final String segment : rule.split(";")) {
            final String trimmed = segment.strip();
            if (trimmed.toUpperCase(Locale.ROOT).startsWith(key)) {
                return trimmed.substring(key.length()).strip().toUpperCase(Locale.ROOT);
            }
        }
        return null;
    }
}
