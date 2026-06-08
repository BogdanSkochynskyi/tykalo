package io.tykalo.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ReminderLevelTest {

    private static final Instant DUE = Instant.parse("2026-06-08T00:00:00Z");

    private static Optional<ReminderLevel> at(final Duration past) {
        return ReminderLevel.maxElapsed(DUE, DUE.plus(past));
    }

    @Test
    void maxElapsed_isEmpty_beforeFirstTier() {
        assertThat(at(Duration.ofMinutes(119))).isEmpty();
    }

    @Test
    void maxElapsed_isL1_exactlyAtTwoHours() {
        assertThat(at(Duration.ofHours(2))).contains(ReminderLevel.L1);
    }

    @Test
    void maxElapsed_isL1_betweenTwoAndSixHours() {
        assertThat(at(Duration.ofHours(5).plusMinutes(59))).contains(ReminderLevel.L1);
    }

    @Test
    void maxElapsed_isL2_exactlyAtSixHours() {
        assertThat(at(Duration.ofHours(6))).contains(ReminderLevel.L2);
    }

    @Test
    void maxElapsed_isL3_exactlyAtTwelveHours() {
        assertThat(at(Duration.ofHours(12))).contains(ReminderLevel.L3);
    }

    @Test
    void maxElapsed_staysL3_wellBeyondTwelveHours() {
        assertThat(at(Duration.ofHours(48))).contains(ReminderLevel.L3);
    }

    @Test
    void hasElapsed_isFalse_justBeforeTier() {
        assertThat(ReminderLevel.L2.hasElapsed(DUE, DUE.plus(Duration.ofHours(6)).minusNanos(1))).isFalse();
    }
}
