package io.tykalo.list;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RecurrenceParserTest {

    private final RecurrenceParser parser = new RecurrenceParser();

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "daily Water plants     | FREQ=DAILY                       | Water plants",
            "everyday Water plants  | FREQ=DAILY                       | Water plants",
            "every day Water plants | FREQ=DAILY                       | Water plants",
            "weekly Team sync       | FREQ=WEEKLY                      | Team sync",
            "every week Team sync   | FREQ=WEEKLY                      | Team sync",
            "weekdays Standup       | FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR | Standup",
            "weekends Clean house   | FREQ=WEEKLY;BYDAY=SA,SU          | Clean house",
            "every Monday Team sync | FREQ=WEEKLY;BYDAY=MO             | Team sync",
            "every fri Drinks       | FREQ=WEEKLY;BYDAY=FR             | Drinks",
    })
    void parses_leadingKeyword(final String input, final String rule, final String title) {
        final RecurrenceParser.Result result = parser.parse(input);

        assertThat(result.recurrenceRule()).isEqualTo(rule);
        assertThat(result.title()).isEqualTo(title);
        assertThat(result.hasRecurrence()).isTrue();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Water plants daily     | FREQ=DAILY                       | Water plants",
            "Team sync weekly       | FREQ=WEEKLY                      | Team sync",
            "Standup weekdays       | FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR | Standup",
            "Clean house weekends   | FREQ=WEEKLY;BYDAY=SA,SU          | Clean house",
            "Team sync every Monday | FREQ=WEEKLY;BYDAY=MO             | Team sync",
    })
    void parses_trailingKeyword(final String input, final String rule, final String title) {
        final RecurrenceParser.Result result = parser.parse(input);

        assertThat(result.recurrenceRule()).isEqualTo(rule);
        assertThat(result.title()).isEqualTo(title);
    }

    @Test
    void isCaseInsensitive() {
        final RecurrenceParser.Result result = parser.parse("DAILY Water plants");

        assertThat(result.recurrenceRule()).isEqualTo("FREQ=DAILY");
        assertThat(result.title()).isEqualTo("Water plants");
    }

    @Test
    void everyWeekday_resolvesEachDay() {
        assertThat(parser.parse("every Sunday Rest").recurrenceRule()).isEqualTo("FREQ=WEEKLY;BYDAY=SU");
        assertThat(parser.parse("every Tuesday Gym").recurrenceRule()).isEqualTo("FREQ=WEEKLY;BYDAY=TU");
        assertThat(parser.parse("every Saturday Hike").recurrenceRule()).isEqualTo("FREQ=WEEKLY;BYDAY=SA");
    }

    @Test
    void leavesMiddleKeywordUntouched() {
        final RecurrenceParser.Result result = parser.parse("buy weekly magazine");

        assertThat(result.recurrenceRule()).isNull();
        assertThat(result.hasRecurrence()).isFalse();
        assertThat(result.title()).isEqualTo("buy weekly magazine");
    }

    @Test
    void noKeyword_keepsWholeTitle() {
        final RecurrenceParser.Result result = parser.parse("Call the dentist");

        assertThat(result.recurrenceRule()).isNull();
        assertThat(result.title()).isEqualTo("Call the dentist");
    }

    @Test
    void everyUnknownWord_isNotRecurrence() {
        final RecurrenceParser.Result result = parser.parse("every now and then check mail");

        assertThat(result.recurrenceRule()).isNull();
        assertThat(result.title()).isEqualTo("every now and then check mail");
    }

    @Test
    void keywordOnly_yieldsEmptyTitle() {
        final RecurrenceParser.Result result = parser.parse("daily");

        assertThat(result.recurrenceRule()).isEqualTo("FREQ=DAILY");
        assertThat(result.title()).isEmpty();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "FREQ=DAILY                       | daily",
            "FREQ=WEEKLY                      | weekly",
            "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR | weekdays",
            "FREQ=WEEKLY;BYDAY=SA,SU          | weekends",
            "FREQ=WEEKLY;BYDAY=MO             | every Monday",
            "FREQ=WEEKLY;BYDAY=SU             | every Sunday",
    })
    void describe_rendersHumanLabel(final String rule, final String label) {
        assertThat(RecurrenceParser.describe(rule)).isEqualTo(label);
    }

    @Test
    void describe_fallsBackToRawRuleWhenUnknown() {
        assertThat(RecurrenceParser.describe("FREQ=MONTHLY")).isEqualTo("FREQ=MONTHLY");
    }
}
