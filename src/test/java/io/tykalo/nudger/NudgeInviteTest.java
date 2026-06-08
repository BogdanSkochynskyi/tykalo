package io.tykalo.nudger;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class NudgeInviteTest {

    @Test
    void payloadFor_thenParse_roundTripsOwnerId() {
        // Arrange
        final UUID ownerId = UUID.fromString("11111111-2222-3333-4444-555555555555");

        // Act
        final String payload = NudgeInvite.payloadFor(ownerId);

        // Assert
        assertThat(payload).startsWith(NudgeInvite.PAYLOAD_PREFIX);
        assertThat(NudgeInvite.parse(payload)).contains(ownerId);
    }

    @Test
    void payload_staysWithinTelegramStartParameterLimits() {
        // Arrange / Act
        final String payload = NudgeInvite.payloadFor(UUID.randomUUID());

        // Assert — Telegram caps the start parameter at 64 chars and allows only [A-Za-z0-9_-]
        assertThat(payload).hasSizeLessThanOrEqualTo(64);
        assertThat(payload).matches("[A-Za-z0-9_-]+");
    }

    @Test
    void parse_returnsEmpty_whenArgumentIsNotAnInvite() {
        assertThat(NudgeInvite.parse(null)).isEmpty();
        assertThat(NudgeInvite.parse("")).isEmpty();
        assertThat(NudgeInvite.parse("some_other_payload")).isEmpty();
    }

    @Test
    void parse_returnsEmpty_whenPayloadIsMalformed() {
        // Not valid base64url
        assertThat(NudgeInvite.parse(NudgeInvite.PAYLOAD_PREFIX + "!!!not-base64!!!")).isEmpty();
    }

    @Test
    void parse_returnsEmpty_whenDecodedBytesAreNotAUuid() {
        // "AAAA" decodes to 3 bytes, far short of the 16 a UUID needs
        assertThat(NudgeInvite.parse(NudgeInvite.PAYLOAD_PREFIX + "AAAA")).isEmpty();
    }
}
