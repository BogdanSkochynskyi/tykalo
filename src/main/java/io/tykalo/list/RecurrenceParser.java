package io.tykalo.list;

import java.time.DayOfWeek;
import java.time.format.TextStyle;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Parses a simple recurrence keyword from the <em>leading or trailing</em> edge of an {@code /add}
 * title and returns the canonical short rule alongside the remaining title. Only the keyword forms
 * below are understood; the full RFC&nbsp;5545 RRULE grammar (BYMONTHDAY, INTERVAL, COUNT, UNTIL …)
 * is deferred to TK-201, so the stored value is a short {@code FREQ=…} fragment, not a complete RRULE.
 *
 * <p>Supported forms (case-insensitive), matched only when they sit at the very start or very end of
 * the text so a keyword inside a title — {@code "buy weekly magazine"} — is left untouched:
 * <ul>
 *   <li>{@code daily} / {@code everyday} / {@code every day} → {@code FREQ=DAILY}</li>
 *   <li>{@code weekly} / {@code every week} → {@code FREQ=WEEKLY}</li>
 *   <li>{@code weekdays} → {@code FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR}</li>
 *   <li>{@code weekends} → {@code FREQ=WEEKLY;BYDAY=SA,SU}</li>
 *   <li>{@code every <weekday>} (full or short name) → {@code FREQ=WEEKLY;BYDAY=XX}</li>
 * </ul>
 *
 * <p>When nothing matches, the whole text is the title and {@link Result#recurrenceRule()} is
 * {@code null}. Leading is tried before trailing.
 */
@Service
public class RecurrenceParser {

    private static final Map<String, DayOfWeek> WEEKDAYS = weekdayLookup();

    /** Two-letter RRULE day codes in week order, used for {@code weekdays}/{@code weekends}. */
    private static final String WEEKDAYS_RULE = "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR";
    private static final String WEEKENDS_RULE = "FREQ=WEEKLY;BYDAY=SA,SU";

    private static final Pattern LEADING;
    private static final Pattern TRAILING;

    static {
        final String weekdayAlt = WEEKDAYS.keySet().stream()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .collect(Collectors.joining("|"));
        final String phrase = "(?:every\\s+(?:" + weekdayAlt + ")|every\\s+day|every\\s+week"
                + "|everyday|daily|weekly|weekdays|weekends)";
        LEADING = Pattern.compile("^" + phrase + "\\b\\s*", Pattern.CASE_INSENSITIVE);
        TRAILING = Pattern.compile("\\s*\\b" + phrase + "$", Pattern.CASE_INSENSITIVE);
    }

    /** Outcome of a parse: the canonical recurrence rule (or {@code null}) and the remaining title. */
    public record Result(@Nullable String recurrenceRule, String title) {

        public boolean hasRecurrence() {
            return recurrenceRule != null;
        }
    }

    /** Strips a recurrence keyword from the leading or trailing edge of {@code text}. */
    public Result parse(final String text) {
        final String input = text.strip();

        final Matcher leading = LEADING.matcher(input);
        if (leading.find()) {
            return new Result(toRule(leading.group()), input.substring(leading.end()).strip());
        }

        final Matcher trailing = TRAILING.matcher(input);
        if (trailing.find()) {
            return new Result(toRule(trailing.group()), input.substring(0, trailing.start()).strip());
        }

        return new Result(null, input);
    }

    /**
     * Renders a human-friendly label for a rule produced by this parser (for the {@code /add} reply),
     * e.g. {@code FREQ=WEEKLY;BYDAY=MO} → {@code "every Monday"}. Falls back to the raw rule for
     * anything it doesn't recognise.
     */
    public static String describe(final String rule) {
        return switch (rule) {
            case "FREQ=DAILY" -> "daily";
            case "FREQ=WEEKLY" -> "weekly";
            case WEEKDAYS_RULE -> "weekdays";
            case WEEKENDS_RULE -> "weekends";
            default -> describeSingleWeekday(rule).orElse(rule);
        };
    }

    private static java.util.Optional<String> describeSingleWeekday(final String rule) {
        final String prefix = "FREQ=WEEKLY;BYDAY=";
        if (!rule.startsWith(prefix)) {
            return java.util.Optional.empty();
        }
        final String code = rule.substring(prefix.length());
        for (final DayOfWeek day : DayOfWeek.values()) {
            if (byDay(day).equals(code)) {
                final String name = day.getDisplayName(TextStyle.FULL, Locale.ENGLISH);
                return java.util.Optional.of("every " + name);
            }
        }
        return java.util.Optional.empty();
    }

    private static @Nullable String toRule(final String phrase) {
        final String p = phrase.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        return switch (p) {
            case "daily", "everyday", "every day" -> "FREQ=DAILY";
            case "weekly", "every week" -> "FREQ=WEEKLY";
            case "weekdays" -> WEEKDAYS_RULE;
            case "weekends" -> WEEKENDS_RULE;
            default -> everyWeekdayRule(p);
        };
    }

    private static @Nullable String everyWeekdayRule(final String phrase) {
        if (!phrase.startsWith("every ")) {
            return null;
        }
        final DayOfWeek day = WEEKDAYS.get(phrase.substring("every ".length()));
        return day == null ? null : "FREQ=WEEKLY;BYDAY=" + byDay(day);
    }

    /** Two-letter RRULE code for a weekday: MO, TU, WE, TH, FR, SA, SU. */
    private static String byDay(final DayOfWeek day) {
        return day.getDisplayName(TextStyle.SHORT, Locale.ENGLISH).substring(0, 2).toUpperCase(Locale.ROOT);
    }

    private static Map<String, DayOfWeek> weekdayLookup() {
        final Map<String, DayOfWeek> map = new LinkedHashMap<>();
        for (final DayOfWeek day : DayOfWeek.values()) {
            map.put(day.getDisplayName(TextStyle.FULL, Locale.ENGLISH).toLowerCase(Locale.ROOT), day);
            map.put(day.getDisplayName(TextStyle.SHORT, Locale.ENGLISH).toLowerCase(Locale.ROOT), day);
        }
        return map;
    }
}
