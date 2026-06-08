package io.tykalo.list;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RecurrenceCalculatorTest {

    private static final ZoneId KYIV = ZoneId.of("Europe/Kyiv");

    private final RecurrenceCalculator calculator = new RecurrenceCalculator();

    @Test
    void daily_advancesByOneDay_preservingTimeOfDay() {
        // Arrange — Mon 2026-06-08 09:00 Kyiv
        final Instant anchor = Instant.parse("2026-06-08T06:00:00Z");

        // Act
        final Optional<Instant> next = calculator.nextOccurrence("FREQ=DAILY", anchor, KYIV);

        // Assert — Tue 2026-06-09 09:00 Kyiv
        assertThat(next).contains(Instant.parse("2026-06-09T06:00:00Z"));
    }

    @Test
    void weekly_advancesBySevenDays() {
        final Instant anchor = Instant.parse("2026-06-08T06:00:00Z");

        final Optional<Instant> next = calculator.nextOccurrence("FREQ=WEEKLY", anchor, KYIV);

        assertThat(next).contains(Instant.parse("2026-06-15T06:00:00Z"));
    }

    @Test
    void weekdays_fromFriday_jumpsToMonday() {
        // Arrange — Fri 2026-06-12 09:00 Kyiv
        final Instant friday = Instant.parse("2026-06-12T06:00:00Z");

        // Act
        final Optional<Instant> next = calculator.nextOccurrence(
                "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR", friday, KYIV);

        // Assert — Mon 2026-06-15 09:00 Kyiv
        assertThat(next).contains(Instant.parse("2026-06-15T06:00:00Z"));
    }

    @Test
    void weekdays_fromMonday_advancesToTuesday() {
        final Instant monday = Instant.parse("2026-06-08T06:00:00Z");

        final Optional<Instant> next = calculator.nextOccurrence(
                "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR", monday, KYIV);

        assertThat(next).contains(Instant.parse("2026-06-09T06:00:00Z"));
    }

    @Test
    void weekends_fromSaturday_advancesToSunday() {
        // Arrange — Sat 2026-06-13 09:00 Kyiv
        final Instant saturday = Instant.parse("2026-06-13T06:00:00Z");

        final Optional<Instant> next = calculator.nextOccurrence(
                "FREQ=WEEKLY;BYDAY=SA,SU", saturday, KYIV);

        // Assert — Sun 2026-06-14 09:00 Kyiv
        assertThat(next).contains(Instant.parse("2026-06-14T06:00:00Z"));
    }

    @Test
    void singleWeekday_advancesByOneWeek() {
        // Arrange — Mon 2026-06-08; rule "every Monday"
        final Instant monday = Instant.parse("2026-06-08T06:00:00Z");

        final Optional<Instant> next = calculator.nextOccurrence("FREQ=WEEKLY;BYDAY=MO", monday, KYIV);

        // Assert — the following Monday
        assertThat(next).contains(Instant.parse("2026-06-15T06:00:00Z"));
    }

    @Test
    void unknownFrequency_yieldsEmpty() {
        final Instant anchor = Instant.parse("2026-06-08T06:00:00Z");

        assertThat(calculator.nextOccurrence("FREQ=MONTHLY", anchor, KYIV)).isEmpty();
    }

    @Test
    void nullOrBlankRule_yieldsEmpty() {
        final Instant anchor = Instant.parse("2026-06-08T06:00:00Z");

        assertThat(calculator.nextOccurrence(null, anchor, KYIV)).isEmpty();
        assertThat(calculator.nextOccurrence("   ", anchor, KYIV)).isEmpty();
    }

    @Test
    void nullAnchor_yieldsEmpty() {
        assertThat(calculator.nextOccurrence("FREQ=DAILY", null, KYIV)).isEmpty();
    }
}
