package io.tykalo.list;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SnoozeParserTest {

    private final SnoozeParser parser = new SnoozeParser();

    private static final ZoneId KYIV = ZoneId.of("Europe/Kyiv");
    // Friday 2026-06-05 10:00 local (Kyiv is UTC+3 in June).
    private static final Instant NOW = ZonedDateTime.of(2026, 6, 5, 10, 0, 0, 0, KYIV).toInstant();

    @Test
    void parses_compactHours() {
        final Optional<Instant> result = parser.parse("1h", KYIV, NOW);

        assertThat(result).contains(NOW.plusSeconds(3600));
    }

    @Test
    void parses_compactDays() {
        final Optional<Instant> result = parser.parse("2d", KYIV, NOW);

        assertThat(result).contains(NOW.plusSeconds(2 * 86_400));
    }

    @Test
    void parses_compactMinutes() {
        assertThat(parser.parse("30m", KYIV, NOW)).contains(NOW.plusSeconds(1_800));
    }

    @Test
    void parses_compactWeeks() {
        assertThat(parser.parse("1w", KYIV, NOW)).contains(NOW.plusSeconds(7 * 86_400));
    }

    @Test
    void parses_spacedAndLongUnitSpellings() {
        assertThat(parser.parse("2 hours", KYIV, NOW)).contains(NOW.plusSeconds(2 * 3600));
        assertThat(parser.parse("3 days", KYIV, NOW)).contains(NOW.plusSeconds(3 * 86_400));
    }

    @Test
    void parses_tomorrow_atNineLocal() {
        final Optional<Instant> result = parser.parse("tomorrow", KYIV, NOW);

        // Saturday 2026-06-06 09:00 Kyiv.
        assertThat(result).contains(ZonedDateTime.of(2026, 6, 6, 9, 0, 0, 0, KYIV).toInstant());
    }

    @Test
    void parses_nextWeek_asComingMondayAtNineLocal() {
        final Optional<Instant> result = parser.parse("next week", KYIV, NOW);

        // NOW is Friday 2026-06-05; the next Monday is 2026-06-08 09:00 Kyiv.
        assertThat(result).contains(ZonedDateTime.of(2026, 6, 8, 9, 0, 0, 0, KYIV).toInstant());
    }

    @Test
    void isCaseInsensitive() {
        assertThat(parser.parse("TOMORROW", KYIV, NOW))
                .contains(ZonedDateTime.of(2026, 6, 6, 9, 0, 0, 0, KYIV).toInstant());
        assertThat(parser.parse("2D", KYIV, NOW)).contains(NOW.plusSeconds(2 * 86_400));
    }

    @Test
    void rejects_zeroAmount() {
        assertThat(parser.parse("0h", KYIV, NOW)).isEmpty();
    }

    @Test
    void rejects_unrecognizedText() {
        assertThat(parser.parse("whenever", KYIV, NOW)).isEmpty();
        assertThat(parser.parse("", KYIV, NOW)).isEmpty();
        assertThat(parser.parse("2", KYIV, NOW)).isEmpty();
    }
}
