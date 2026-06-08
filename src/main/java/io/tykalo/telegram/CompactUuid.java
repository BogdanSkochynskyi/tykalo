package io.tykalo.telegram;

import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * Encodes a {@link UUID} as a 22-character URL-safe Base64 string and back. Telegram caps inline
 * {@code callback_data} at 64 bytes, which the canonical 36-char UUID text blows through as soon as a
 * callback must carry two ids (e.g. TK-158's {@code task:nudger} assignment buttons). Packing the 16
 * raw bytes drops each id to 22 chars, so two ids plus a short prefix fit comfortably.
 */
public final class CompactUuid {

    private CompactUuid() {
    }

    /** The 16-byte form of {@code id} as 22 URL-safe Base64 chars (no padding). */
    public static String encode(final UUID id) {
        final ByteBuffer buffer = ByteBuffer.allocate(16);
        buffer.putLong(id.getMostSignificantBits());
        buffer.putLong(id.getLeastSignificantBits());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer.array());
    }

    /** Reverses {@link #encode(UUID)}; empty when {@code encoded} is not a valid 16-byte token. */
    public static Optional<UUID> decode(final String encoded) {
        try {
            final byte[] bytes = Base64.getUrlDecoder().decode(encoded);
            if (bytes.length != 16) {
                return Optional.empty();
            }
            final ByteBuffer buffer = ByteBuffer.wrap(bytes);
            return Optional.of(new UUID(buffer.getLong(), buffer.getLong()));
        } catch (final IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
