package io.tykalo.nudger;

import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Codec for the {@code /start} deep-link payload that carries a nudger invitation. When an owner
 * invites someone who is not on the bot yet (TK-152), we hand them a link of the form
 * {@code t.me/<bot>?start=nudge_invite_<base64>}; clicking it sends {@code /start <payload>} so the
 * bot can wire the new user up as the owner's pending nudger.
 *
 * <p>The payload is the owner's UUID (16 bytes) in unpadded base64url — {@value #PAYLOAD_PREFIX}
 * plus 22 chars, well within Telegram's 64-char start-parameter limit and its {@code [A-Za-z0-9_-]}
 * alphabet. Decoding is total: any malformed or non-invite argument yields {@link Optional#empty()}.
 */
public final class NudgeInvite {

    public static final String PAYLOAD_PREFIX = "nudge_invite_";

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private static final int UUID_BYTES = 16;

    private NudgeInvite() {
    }

    /** The full {@code /start} payload that encodes {@code ownerId} for an invitation. */
    public static String payloadFor(final UUID ownerId) {
        final ByteBuffer buffer = ByteBuffer.allocate(UUID_BYTES);
        buffer.putLong(ownerId.getMostSignificantBits());
        buffer.putLong(ownerId.getLeastSignificantBits());
        return PAYLOAD_PREFIX + ENCODER.encodeToString(buffer.array());
    }

    /**
     * Decodes the owner id from a {@code /start} argument, or empty if it is absent, not an invite
     * payload, or malformed.
     */
    public static Optional<UUID> parse(final @Nullable String startArg) {
        if (startArg == null || !startArg.startsWith(PAYLOAD_PREFIX)) {
            return Optional.empty();
        }
        final String encoded = startArg.substring(PAYLOAD_PREFIX.length());
        try {
            final byte[] bytes = DECODER.decode(encoded);
            if (bytes.length != UUID_BYTES) {
                return Optional.empty();
            }
            final ByteBuffer buffer = ByteBuffer.wrap(bytes);
            return Optional.of(new UUID(buffer.getLong(), buffer.getLong()));
        } catch (final IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
