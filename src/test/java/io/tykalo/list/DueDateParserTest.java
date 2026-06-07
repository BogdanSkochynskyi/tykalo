package io.tykalo.list;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

class DueDateParserTest {

    private static final ZoneId KYIV = ZoneId.of("Europe/Kyiv");

    private final DueDateParser parser = new DueDateParser();

    /** Sunday 2026-06-07, 12:00 local Kyiv — the fixed "now" all relative cases resolve against. */
    private final Instant now = ZonedDateTime.of(2026, 6, 7, 12, 0, 0, 0, KYIV).toInstant();

    private DueDateParser.Result parse(final String text) {
        return parser.parse(text, KYIV, now);
    }

    private Instant kyiv(final int year, final int month, final int day, final int hour, final int minute) {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, KYIV).toInstant();
    }

    @Test
    void parses_isoDateTime() {
        final DueDateParser.Result result = parse("2026-06-15 14:00 Submit report");

        assertThat(result.dueAt()).isEqualTo(kyiv(2026, 6, 15, 14, 0));
        assertThat(result.title()).isEqualTo("Submit report");
    }

    @Test
    void parses_isoDateTime_withTSeparator() {
        final DueDateParser.Result result = parse("2026-06-15T14:00 Submit report");

        assertThat(result.dueAt()).isEqualTo(kyiv(2026, 6, 15, 14, 0));
        assertThat(result.title()).isEqualTo("Submit report");
    }

    @Test
    void parses_isoDateOnly_atDefaultHour() {
        final DueDateParser.Result result = parse("2026-06-15 Pay rent");

        assertThat(result.dueAt()).isEqualTo(kyiv(2026, 6, 15, DueDateParser.DEFAULT_HOUR, 0));
        assertThat(result.title()).isEqualTo("Pay rent");
    }

    @Test
    void parses_relativeHours() {
        final DueDateParser.Result result = parse("in 2 hours Stretch");

        assertThat(result.dueAt()).isEqualTo(now.plusSeconds(2 * 3600));
        assertThat(result.title()).isEqualTo("Stretch");
    }

    @Test
    void parses_relativeMinutes_shortForm() {
        final DueDateParser.Result result = parse("in 30 min Make tea");

        assertThat(result.dueAt()).isEqualTo(now.plusSeconds(30 * 60));
        assertThat(result.title()).isEqualTo("Make tea");
    }

    @Test
    void parses_relativeDays() {
        final DueDateParser.Result result = parse("in 3 days Review PR");

        assertThat(result.dueAt()).isEqualTo(now.plusSeconds(3 * 86_400));
        assertThat(result.title()).isEqualTo("Review PR");
    }

    @Test
    void parses_today_withTime() {
        final DueDateParser.Result result = parse("today 18:00 Call mom");

        assertThat(result.dueAt()).isEqualTo(kyiv(2026, 6, 7, 18, 0));
        assertThat(result.title()).isEqualTo("Call mom");
    }

    @Test
    void parses_tomorrow_withAmPm() {
        final DueDateParser.Result result = parse("tomorrow 9am Buy milk");

        assertThat(result.dueAt()).isEqualTo(kyiv(2026, 6, 8, 9, 0));
        assertThat(result.title()).isEqualTo("Buy milk");
    }

    @Test
    void parses_tomorrow_bare_atDefaultHour() {
        final DueDateParser.Result result = parse("tomorrow Plan the week");

        assertThat(result.dueAt()).isEqualTo(kyiv(2026, 6, 8, DueDateParser.DEFAULT_HOUR, 0));
        assertThat(result.title()).isEqualTo("Plan the week");
    }

    @Test
    void parses_nextWeekday_atDefaultHour() {
        final DueDateParser.Result result = parse("next Monday Review roadmap");

        final ZonedDateTime due = result.dueAt().atZone(KYIV);
        assertThat(due.getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
        assertThat(due.toLocalTime().getHour()).isEqualTo(DueDateParser.DEFAULT_HOUR);
        assertThat(due.toLocalDate()).isAfter(LocalDate.of(2026, 6, 7));
        assertThat(result.title()).isEqualTo("Review roadmap");
    }

    @Test
    void parses_nextWeekday_withTime() {
        final DueDateParser.Result result = parse("next Monday 14:00 Standup");

        final ZonedDateTime due = result.dueAt().atZone(KYIV);
        assertThat(due.getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
        assertThat(due.toLocalTime().getHour()).isEqualTo(14);
        assertThat(result.title()).isEqualTo("Standup");
    }

    @Test
    void parses_noon_and_midnight_meridiem() {
        assertThat(parse("today 12pm Lunch").dueAt()).isEqualTo(kyiv(2026, 6, 7, 12, 0));
        assertThat(parse("today 12am Sleep").dueAt()).isEqualTo(kyiv(2026, 6, 7, 0, 0));
    }

    @Test
    void noDeadline_keepsWholeTextAsTitle() {
        final DueDateParser.Result result = parse("Buy milk and eggs");

        assertThat(result.hasDueDate()).isFalse();
        assertThat(result.dueAt()).isNull();
        assertThat(result.title()).isEqualTo("Buy milk and eggs");
    }

    @Test
    void preservesTitleCase() {
        final DueDateParser.Result result = parse("tomorrow Buy MILK now");

        assertThat(result.title()).isEqualTo("Buy MILK now");
    }

    @Test
    void doesNotEatBareNumberAsTime() {
        final DueDateParser.Result result = parse("tomorrow 5 apples");

        assertThat(result.dueAt()).isEqualTo(kyiv(2026, 6, 8, DueDateParser.DEFAULT_HOUR, 0));
        assertThat(result.title()).isEqualTo("5 apples");
    }

    @Test
    void invalidIsoDate_fallsThroughToTitle() {
        final DueDateParser.Result result = parse("2026-13-40 Whoops");

        assertThat(result.hasDueDate()).isFalse();
        assertThat(result.title()).isEqualTo("2026-13-40 Whoops");
    }

    @Test
    void pastDate_isStillParsed() {
        final DueDateParser.Result result = parse("2020-01-01 10:00 Old task");

        assertThat(result.dueAt()).isEqualTo(kyiv(2020, 1, 1, 10, 0));
        assertThat(result.dueAt()).isBefore(now);
        assertThat(result.title()).isEqualTo("Old task");
    }

    @Test
    void deadlineWithoutTitle_yieldsEmptyTitle() {
        final DueDateParser.Result result = parse("tomorrow 9am");

        assertThat(result.dueAt()).isEqualTo(kyiv(2026, 6, 8, 9, 0));
        assertThat(result.title()).isEmpty();
    }
}
