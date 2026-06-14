package io.tykalo.list;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ListInviteTest {

    @Test
    void payloadFor_thenParse_roundTripsAllFields() {
        // Arrange
        final UUID listId = UUID.fromString("11111111-2222-3333-4444-555555555555");
        final UUID invitedBy = UUID.fromString("66666666-7777-8888-9999-aaaaaaaaaaaa");

        // Act
        final String payload = ListInvite.payloadFor(listId, invitedBy, ListMemberRole.EDITOR);
        final Optional<ListInvite.Payload> parsed = ListInvite.parse(payload);

        // Assert
        assertThat(payload).startsWith(ListInvite.PAYLOAD_PREFIX);
        assertThat(parsed).contains(new ListInvite.Payload(listId, invitedBy, ListMemberRole.EDITOR));
    }

    @Test
    void payloadFor_carriesTheChosenRole() {
        final String payload = ListInvite.payloadFor(UUID.randomUUID(), UUID.randomUUID(), ListMemberRole.MEMBER);

        assertThat(ListInvite.parse(payload)).get()
                .extracting(ListInvite.Payload::role)
                .isEqualTo(ListMemberRole.MEMBER);
    }

    @Test
    void payload_staysWithinTelegramStartParameterLimits() {
        // Telegram caps the start parameter at 64 chars and allows only [A-Za-z0-9_-]
        final String payload = ListInvite.payloadFor(UUID.randomUUID(), UUID.randomUUID(), ListMemberRole.MEMBER);

        assertThat(payload).hasSizeLessThanOrEqualTo(64);
        assertThat(payload).matches("[A-Za-z0-9_-]+");
    }

    @Test
    void parse_returnsEmpty_whenArgumentIsNotAnInvite() {
        assertThat(ListInvite.parse(null)).isEmpty();
        assertThat(ListInvite.parse("")).isEmpty();
        assertThat(ListInvite.parse("nudge_invite_abc")).isEmpty();
    }

    @Test
    void parse_returnsEmpty_whenPayloadIsMalformed() {
        assertThat(ListInvite.parse(ListInvite.PAYLOAD_PREFIX + "!!!not-base64!!!")).isEmpty();
    }

    @Test
    void parse_returnsEmpty_whenDecodedBytesHaveWrongLength() {
        // "AAAA" decodes to 3 bytes, far short of the 33 a full payload needs
        assertThat(ListInvite.parse(ListInvite.PAYLOAD_PREFIX + "AAAA")).isEmpty();
    }
}
