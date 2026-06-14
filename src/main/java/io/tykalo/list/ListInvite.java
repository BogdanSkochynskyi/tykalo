package io.tykalo.list;

import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Codec for the {@code /start} deep-link payload that carries a shared-list invitation (TK-193). When a
 * member shares a list via link we hand them {@code t.me/<bot>?start=list_invite_<base64>}; clicking it
 * sends {@code /start <payload>} so the bot can wire the clicker up as a pending member of the encoded
 * list with the encoded role.
 *
 * <p>The payload encodes {@code listId} (16 bytes), {@code invitedBy} (16 bytes) and the
 * {@link ListMemberRole} (1 byte ordinal) in unpadded base64url — {@value #PAYLOAD_PREFIX} plus 44
 * chars, comfortably within Telegram's 64-char start-parameter limit and its {@code [A-Za-z0-9_-]}
 * alphabet. Only EDITOR/MEMBER are ever encoded (a link never grants OWNER); decoding is total, so any
 * malformed, non-invite or out-of-range argument yields {@link Optional#empty()}.
 */
public final class ListInvite {

    public static final String PAYLOAD_PREFIX = "list_invite_";

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private static final int PAYLOAD_BYTES = 33; // 16 (listId) + 16 (invitedBy) + 1 (role ordinal)
    private static final ListMemberRole[] ROLES = ListMemberRole.values();

    private ListInvite() {
    }

    /** The decoded invitation: which list, who invited, and the role being offered. */
    public record Payload(UUID listId, UUID invitedBy, ListMemberRole role) {
    }

    /** The full {@code /start} payload encoding an invitation to {@code listId} as {@code role}, from {@code invitedBy}. */
    public static String payloadFor(final UUID listId, final UUID invitedBy, final ListMemberRole role) {
        final ByteBuffer buffer = ByteBuffer.allocate(PAYLOAD_BYTES);
        putUuid(buffer, listId);
        putUuid(buffer, invitedBy);
        buffer.put((byte) role.ordinal());
        return PAYLOAD_PREFIX + ENCODER.encodeToString(buffer.array());
    }

    /**
     * Decodes an invitation from a {@code /start} argument, or empty if it is absent, not an invite
     * payload, malformed, or carries an unknown role ordinal.
     */
    public static Optional<Payload> parse(final @Nullable String startArg) {
        if (startArg == null || !startArg.startsWith(PAYLOAD_PREFIX)) {
            return Optional.empty();
        }
        final String encoded = startArg.substring(PAYLOAD_PREFIX.length());
        try {
            final byte[] bytes = DECODER.decode(encoded);
            if (bytes.length != PAYLOAD_BYTES) {
                return Optional.empty();
            }
            final ByteBuffer buffer = ByteBuffer.wrap(bytes);
            final UUID listId = new UUID(buffer.getLong(), buffer.getLong());
            final UUID invitedBy = new UUID(buffer.getLong(), buffer.getLong());
            final int roleOrdinal = buffer.get();
            if (roleOrdinal < 0 || roleOrdinal >= ROLES.length) {
                return Optional.empty();
            }
            return Optional.of(new Payload(listId, invitedBy, ROLES[roleOrdinal]));
        } catch (final IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static void putUuid(final ByteBuffer buffer, final UUID id) {
        buffer.putLong(id.getMostSignificantBits());
        buffer.putLong(id.getLeastSignificantBits());
    }
}
