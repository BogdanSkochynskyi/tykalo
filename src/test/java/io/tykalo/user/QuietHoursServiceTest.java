package io.tykalo.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class QuietHoursServiceTest {

    private final QuietHoursService service = new QuietHoursService();

    private static User userWith(final ZoneId zone, final LocalTime start, final LocalTime end) {
        final User user = User.create(42L, "bob", zone, "uk");
        user.setQuietHoursStart(start);
        user.setQuietHoursEnd(end);
        return user;
    }

    private static Instant utc(final int hour, final int minute) {
        return LocalTime.of(hour, minute).atDate(java.time.LocalDate.of(2026, 6, 7))
                .atZone(ZoneId.of("UTC")).toInstant();
    }

    @Test
    void isQuiet_true_insideMidnightCrossingWindow_lateEvening() {
        // Arrange
        final User user = userWith(ZoneId.of("UTC"), LocalTime.of(22, 0), LocalTime.of(7, 0));

        // Act + Assert
        assertThat(service.isQuiet(user, utc(23, 30))).isTrue();
    }

    @Test
    void isQuiet_true_insideMidnightCrossingWindow_earlyMorning() {
        final User user = userWith(ZoneId.of("UTC"), LocalTime.of(22, 0), LocalTime.of(7, 0));

        assertThat(service.isQuiet(user, utc(3, 0))).isTrue();
    }

    @Test
    void isQuiet_false_outsideMidnightCrossingWindow() {
        final User user = userWith(ZoneId.of("UTC"), LocalTime.of(22, 0), LocalTime.of(7, 0));

        assertThat(service.isQuiet(user, utc(12, 0))).isFalse();
    }

    @Test
    void isQuiet_true_insideSameDayWindow() {
        final User user = userWith(ZoneId.of("UTC"), LocalTime.of(13, 0), LocalTime.of(15, 0));

        assertThat(service.isQuiet(user, utc(14, 0))).isTrue();
    }

    @Test
    void isQuiet_false_outsideSameDayWindow() {
        final User user = userWith(ZoneId.of("UTC"), LocalTime.of(13, 0), LocalTime.of(15, 0));

        assertThat(service.isQuiet(user, utc(16, 0))).isFalse();
    }

    @Test
    void isQuiet_true_atStartBoundary_inclusive() {
        final User user = userWith(ZoneId.of("UTC"), LocalTime.of(22, 0), LocalTime.of(7, 0));

        assertThat(service.isQuiet(user, utc(22, 0))).isTrue();
    }

    @Test
    void isQuiet_false_atEndBoundary_exclusive() {
        final User user = userWith(ZoneId.of("UTC"), LocalTime.of(22, 0), LocalTime.of(7, 0));

        assertThat(service.isQuiet(user, utc(7, 0))).isFalse();
    }

    @Test
    void isQuiet_false_whenBoundsNull() {
        final User user = userWith(ZoneId.of("UTC"), null, null);

        assertThat(service.isQuiet(user, utc(3, 0))).isFalse();
    }

    @Test
    void isQuiet_false_whenBoundsEqual_emptyWindow() {
        final User user = userWith(ZoneId.of("UTC"), LocalTime.of(22, 0), LocalTime.of(22, 0));

        assertThat(service.isQuiet(user, utc(22, 0))).isFalse();
    }

    @Test
    void isQuiet_usesUserTimezone_notUtc() {
        // Kyiv is UTC+3 in June; 23:00 Kyiv == 20:00 UTC, which is inside 22:00–07:00 Kyiv-local.
        final User user = userWith(ZoneId.of("Europe/Kyiv"), LocalTime.of(22, 0), LocalTime.of(7, 0));

        assertThat(service.isQuiet(user, utc(20, 0))).isTrue();
    }

    @Test
    void isQuiet_defaultsToUtc_whenZoneUnset() {
        final User user = userWith(null, LocalTime.of(22, 0), LocalTime.of(7, 0));

        assertThat(service.isQuiet(user, utc(23, 0))).isTrue();
    }

    @Test
    void nextActiveAt_returnsInstantUnchanged_whenNotQuiet() {
        final User user = userWith(ZoneId.of("UTC"), LocalTime.of(22, 0), LocalTime.of(7, 0));
        final Instant noon = utc(12, 0);

        assertThat(service.nextActiveAt(user, noon)).isEqualTo(noon);
    }

    @Test
    void nextActiveAt_returnsEndOfQuietHours_whenQuietLateEvening() {
        // 23:00 on 2026-06-07 is quiet (22:00–07:00); the window ends at 07:00 the next morning.
        final User user = userWith(ZoneId.of("UTC"), LocalTime.of(22, 0), LocalTime.of(7, 0));

        assertThat(service.nextActiveAt(user, utc(23, 0)))
                .isEqualTo(LocalTime.of(7, 0).atDate(java.time.LocalDate.of(2026, 6, 8))
                        .atZone(ZoneId.of("UTC")).toInstant());
    }

    @Test
    void nextActiveAt_returnsEndSameDay_whenQuietEarlyMorning() {
        // 03:00 is quiet; the window ends at 07:00 the same day.
        final User user = userWith(ZoneId.of("UTC"), LocalTime.of(22, 0), LocalTime.of(7, 0));

        assertThat(service.nextActiveAt(user, utc(3, 0))).isEqualTo(utc(7, 0));
    }

    @Test
    void nextActiveAt_respectsUserTimezone() {
        // Kyiv is UTC+3 in June; quiet 22:00–07:00 Kyiv. 20:00 UTC = 23:00 Kyiv (quiet) → ends 07:00 Kyiv = 04:00 UTC next day.
        final User user = userWith(ZoneId.of("Europe/Kyiv"), LocalTime.of(22, 0), LocalTime.of(7, 0));

        assertThat(service.nextActiveAt(user, utc(20, 0)))
                .isEqualTo(LocalTime.of(4, 0).atDate(java.time.LocalDate.of(2026, 6, 8))
                        .atZone(ZoneId.of("UTC")).toInstant());
    }
}
