package io.tykalo.user;

import java.time.ZoneId;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Best-effort mapping from a Telegram {@code language_code} (an IETF tag such as
 * {@code "uk"} or {@code "en-US"}) to a {@link ZoneId}. Only the primary subtag is
 * considered. Anything unknown or missing falls back to {@code Europe/Kyiv}; users
 * can correct it later via {@code /tz}.
 */
@Component
public class TimezoneResolver {

    static final ZoneId DEFAULT_ZONE = ZoneId.of("Europe/Kyiv");

    private static final Map<String, ZoneId> ZONE_BY_LANGUAGE = Map.ofEntries(
            Map.entry("uk", ZoneId.of("Europe/Kyiv")),
            Map.entry("pl", ZoneId.of("Europe/Warsaw")),
            Map.entry("de", ZoneId.of("Europe/Berlin")),
            Map.entry("fr", ZoneId.of("Europe/Paris")),
            Map.entry("es", ZoneId.of("Europe/Madrid")),
            Map.entry("it", ZoneId.of("Europe/Rome")),
            Map.entry("pt", ZoneId.of("Europe/Lisbon")),
            Map.entry("cs", ZoneId.of("Europe/Prague")),
            Map.entry("sk", ZoneId.of("Europe/Bratislava")),
            Map.entry("ro", ZoneId.of("Europe/Bucharest")),
            Map.entry("en", ZoneId.of("Europe/London")));

    public ZoneId resolve(final @Nullable String languageCode) {
        if (languageCode == null || languageCode.isBlank()) {
            return DEFAULT_ZONE;
        }
        final String primarySubtag = languageCode.toLowerCase(Locale.ROOT).split("-", 2)[0];
        return ZONE_BY_LANGUAGE.getOrDefault(primarySubtag, DEFAULT_ZONE);
    }
}
