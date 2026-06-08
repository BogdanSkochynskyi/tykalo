package io.tykalo.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class CompactUuidTest {

    @Test
    void encode_thenDecode_roundTripsTheSameUuid() {
        // Arrange
        final UUID id = UUID.fromString("0123abcd-4567-89ef-fedc-ba9876543210");

        // Act
        final String encoded = CompactUuid.encode(id);

        // Assert
        assertThat(CompactUuid.decode(encoded)).contains(id);
    }

    @Test
    void encode_packsToTwentyTwoUrlSafeChars() {
        // Act
        final String encoded = CompactUuid.encode(UUID.randomUUID());

        // Assert — short enough that two ids plus a prefix fit Telegram's 64-byte callback limit
        assertThat(encoded).hasSize(22).doesNotContain("+", "/", "=");
        assertThat(("tn:a:" + encoded + ":" + encoded).length()).isLessThanOrEqualTo(64);
    }

    @Test
    void decode_returnsEmpty_forGarbage() {
        assertThat(CompactUuid.decode("not-base64!!")).isEmpty();
    }

    @Test
    void decode_returnsEmpty_whenTokenIsNotSixteenBytes() {
        // "AAAA" decodes to 3 bytes, not 16
        assertThat(CompactUuid.decode("AAAA")).isEmpty();
    }
}
