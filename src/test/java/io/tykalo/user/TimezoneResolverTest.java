package io.tykalo.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class TimezoneResolverTest {

    private final TimezoneResolver resolver = new TimezoneResolver();

    @ParameterizedTest
    @CsvSource({
            "uk, Europe/Kyiv",
            "pl, Europe/Warsaw",
            "de, Europe/Berlin",
            "en, Europe/London"
    })
    void resolve_mapsKnownLanguageCode_toZone(final String code, final String expectedZone) {
        assertThat(resolver.resolve(code)).isEqualTo(ZoneId.of(expectedZone));
    }

    @Test
    void resolve_stripsRegionSubtag_beforeLookup() {
        assertThat(resolver.resolve("EN-US")).isEqualTo(ZoneId.of("Europe/London"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "xx", "zz-ZZ"})
    void resolve_fallsBackToKyiv_whenMissingOrUnknown(final String code) {
        assertThat(resolver.resolve(code)).isEqualTo(ZoneId.of("Europe/Kyiv"));
    }
}
