package io.tykalo.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class ZoneIdConverterTest {

    private final ZoneIdConverter converter = new ZoneIdConverter();

    @Test
    void convertToDatabaseColumn_writesZoneId() {
        assertThat(converter.convertToDatabaseColumn(ZoneId.of("Europe/Kyiv"))).isEqualTo("Europe/Kyiv");
    }

    @Test
    void convertToEntityAttribute_readsZoneId() {
        assertThat(converter.convertToEntityAttribute("Europe/Berlin")).isEqualTo(ZoneId.of("Europe/Berlin"));
    }

    @Test
    void converter_roundTripsNull() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }
}
