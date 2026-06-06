package io.tykalo.common;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.time.ZoneId;
import org.jspecify.annotations.Nullable;

/**
 * Persists a {@link ZoneId} as its string id (e.g. {@code "Europe/Kyiv"}) so the
 * {@code timezone} column stays human-readable. Applied explicitly via {@code @Convert}.
 */
@Converter
public class ZoneIdConverter implements AttributeConverter<ZoneId, String> {

    @Override
    public @Nullable String convertToDatabaseColumn(final @Nullable ZoneId attribute) {
        return attribute == null ? null : attribute.getId();
    }

    @Override
    public @Nullable ZoneId convertToEntityAttribute(final @Nullable String dbData) {
        return dbData == null ? null : ZoneId.of(dbData);
    }
}
