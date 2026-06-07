package io.tykalo.list;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.TextStyle;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Parses an optional deadline from the <em>leading prefix</em> of a {@code /add} argument string
 * and returns it alongside the remaining title. Resolution is deterministic and timezone-aware;
 * richer natural-language understanding (LLM fallback) is deferred to TK-311.
 *
 * <p>Supported leading forms (case-insensitive), first match wins:
 * <ul>
 *   <li>ISO datetime — {@code 2026-06-15 14:00} or {@code 2026-06-15T14:00}</li>
 *   <li>ISO date — {@code 2026-06-15} (defaults to {@value #DEFAULT_HOUR}:00 local)</li>
 *   <li>relative — {@code in 2 hours}, {@code in 30 min}, {@code in 3 days}, {@code in 1 week}</li>
 *   <li>{@code today} / {@code tomorrow} with an optional time ({@code tomorrow 9am})</li>
 *   <li>{@code next <weekday>} with an optional time ({@code next Monday 14:00})</li>
 * </ul>
 *
 * <p>A bare number is never read as a time, so {@code "tomorrow 5 apples"} keeps {@code "5 apples"}
 * in the title — a time needs a {@code :} or an am/pm marker. When nothing matches, the whole text
 * is the title and {@link Result#dueAt()} is {@code null}.
 */
@Service
public class DueDateParser {

    static final int DEFAULT_HOUR = 9;

    private static final LocalTime DEFAULT_TIME = LocalTime.of(DEFAULT_HOUR, 0);

    /** A time token: {@code 14:00}, {@code 9am}, {@code 9:30 pm}, {@code 12pm} — but not a bare number. */
    private static final String TIME = "(\\d{1,2}:\\d{2}(?:\\s*[ap]m)?|\\d{1,2}\\s*[ap]m)";

    private static final Pattern ISO_DATETIME =
            Pattern.compile("^(\\d{4}-\\d{2}-\\d{2})[ T](\\d{1,2}:\\d{2})\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern ISO_DATE =
            Pattern.compile("^(\\d{4}-\\d{2}-\\d{2})\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern RELATIVE = Pattern.compile(
            "^in\\s+(\\d+)\\s+(minutes?|mins?|hours?|hrs?|days?|weeks?)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern DAY_KEYWORD = Pattern.compile(
            "^(today|tomorrow)\\b(?:\\s+" + TIME + ")?", Pattern.CASE_INSENSITIVE);
    private static final Pattern NEXT_WEEKDAY = Pattern.compile(
            "^next\\s+(\\p{L}+)\\b(?:\\s+" + TIME + ")?", Pattern.CASE_INSENSITIVE);
    private static final Pattern TIME_TOKEN =
            Pattern.compile("(\\d{1,2})(?::(\\d{2}))?\\s*([ap]m)?", Pattern.CASE_INSENSITIVE);

    private static final Map<String, DayOfWeek> WEEKDAYS = weekdayLookup();

    /** Outcome of a parse: the resolved deadline (or {@code null}) and the remaining task title. */
    public record Result(@Nullable Instant dueAt, String title) {

        public boolean hasDueDate() {
            return dueAt != null;
        }
    }

    /**
     * Extracts a leading deadline from {@code text}, resolving relative expressions against
     * {@code now} in the caller's {@code zone}. The title is the (case-preserving) remainder.
     */
    public Result parse(final String text, final ZoneId zone, final Instant now) {
        final String input = text.strip();
        final ZonedDateTime reference = now.atZone(zone);

        final Matcher isoDateTime = ISO_DATETIME.matcher(input);
        if (isoDateTime.find()) {
            final LocalDate date = tryParseDate(isoDateTime.group(1));
            final LocalTime time = parseTime(isoDateTime.group(2));
            if (date != null && time != null) {
                return resolved(date.atTime(time), zone, input, isoDateTime.end());
            }
        }

        final Matcher isoDate = ISO_DATE.matcher(input);
        if (isoDate.find()) {
            final LocalDate date = tryParseDate(isoDate.group(1));
            if (date != null) {
                return resolved(date.atTime(DEFAULT_TIME), zone, input, isoDate.end());
            }
        }

        final Matcher relative = RELATIVE.matcher(input);
        if (relative.find()) {
            final Instant due = reference.plus(amountOf(relative.group(1), relative.group(2))).toInstant();
            return new Result(due, input.substring(relative.end()).strip());
        }

        final Matcher dayKeyword = DAY_KEYWORD.matcher(input);
        if (dayKeyword.find()) {
            final LocalTime time = timeOrDefault(dayKeyword.group(2));
            if (time != null) {
                final LocalDate base = reference.toLocalDate();
                final LocalDate date = "tomorrow".equalsIgnoreCase(dayKeyword.group(1)) ? base.plusDays(1) : base;
                return resolved(date.atTime(time), zone, input, dayKeyword.end());
            }
        }

        final Matcher nextWeekday = NEXT_WEEKDAY.matcher(input);
        if (nextWeekday.find()) {
            final DayOfWeek target = WEEKDAYS.get(nextWeekday.group(1).toLowerCase(Locale.ROOT));
            final LocalTime time = timeOrDefault(nextWeekday.group(2));
            if (target != null && time != null) {
                final LocalDate date = nextOccurrence(reference.toLocalDate(), target);
                return resolved(date.atTime(time), zone, input, nextWeekday.end());
            }
        }

        return new Result(null, input);
    }

    private Result resolved(final java.time.LocalDateTime localDue, final ZoneId zone,
                            final String input, final int matchEnd) {
        final Instant due = localDue.atZone(zone).toInstant();
        return new Result(due, input.substring(matchEnd).strip());
    }

    private static Duration amountOf(final String count, final String unit) {
        final long n = Long.parseLong(count);
        final String u = unit.toLowerCase(Locale.ROOT);
        if (u.startsWith("min")) {
            return Duration.ofMinutes(n);
        }
        if (u.startsWith("hour") || u.startsWith("hr")) {
            return Duration.ofHours(n);
        }
        if (u.startsWith("day")) {
            return Duration.ofDays(n);
        }
        return Duration.ofDays(n * 7);
    }

    private static LocalDate nextOccurrence(final LocalDate from, final DayOfWeek target) {
        LocalDate candidate = from.plusDays(1);
        while (candidate.getDayOfWeek() != target) {
            candidate = candidate.plusDays(1);
        }
        return candidate;
    }

    /** Resolves an optional captured time token, defaulting to {@link #DEFAULT_TIME} when absent. */
    private static @Nullable LocalTime timeOrDefault(final @Nullable String token) {
        return token == null ? DEFAULT_TIME : parseTime(token);
    }

    private static @Nullable LocalDate tryParseDate(final String token) {
        try {
            return LocalDate.parse(token);
        } catch (final java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    private static @Nullable LocalTime parseTime(final String token) {
        final Matcher m = TIME_TOKEN.matcher(token.strip());
        if (!m.matches()) {
            return null;
        }
        int hour = Integer.parseInt(m.group(1));
        final int minute = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
        final String meridiem = m.group(3) == null ? null : m.group(3).toLowerCase(Locale.ROOT);
        if (meridiem != null) {
            if (hour < 1 || hour > 12) {
                return null;
            }
            if ("am".equals(meridiem)) {
                hour = hour == 12 ? 0 : hour;
            } else {
                hour = hour == 12 ? 12 : hour + 12;
            }
        }
        if (hour > 23 || minute > 59) {
            return null;
        }
        return LocalTime.of(hour, minute);
    }

    private static Map<String, DayOfWeek> weekdayLookup() {
        final Map<String, DayOfWeek> map = new LinkedHashMap<>();
        for (final DayOfWeek day : DayOfWeek.values()) {
            final String full = day.getDisplayName(TextStyle.FULL, Locale.ENGLISH).toLowerCase(Locale.ROOT);
            final String shortName = day.getDisplayName(TextStyle.SHORT, Locale.ENGLISH).toLowerCase(Locale.ROOT);
            map.put(full, day);
            map.put(shortName, day);
        }
        return map;
    }
}
