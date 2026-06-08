package io.tykalo.nudger.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.STRING;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.tykalo.nudger.AckResult;
import io.tykalo.nudger.NudgeAckService;
import io.tykalo.telegram.TelegramMessageGateway;
import io.tykalo.user.User;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;

@ExtendWith(MockitoExtension.class)
class NudgeAckCallbackHandlerTest {

    private static final long OWNER_CHAT_ID = 42L;

    @Mock
    private NudgeAckService nudgeAckService;
    @Mock
    private TelegramMessageGateway gateway;

    @InjectMocks
    private NudgeAckCallbackHandler handler;

    @Test
    void ack_thanksNudger_andNotifiesOwnerWithMonthlyCount() {
        // Arrange
        final UUID logId = UUID.randomUUID();
        when(nudgeAckService.acknowledge(eq(logId), any()))
                .thenReturn(new AckResult.Acknowledged(owner(), "@bob", 3L));

        // Act
        final Optional<String> toast = handler.handle(callback("nudge:ack:" + logId));

        // Assert
        assertThat(toast).get(STRING).contains("Thanks for the nudge");
        verify(gateway).sendMarkdown(eq(OWNER_CHAT_ID), contains("@bob reminded you 3 times this month"), isNull());
    }

    @Test
    void ack_usesSingular_whenRemindedOnce() {
        final UUID logId = UUID.randomUUID();
        when(nudgeAckService.acknowledge(eq(logId), any()))
                .thenReturn(new AckResult.Acknowledged(owner(), "@bob", 1L));

        handler.handle(callback("nudge:ack:" + logId));

        verify(gateway).sendMarkdown(eq(OWNER_CHAT_ID), contains("reminded you 1 time this month"), isNull());
    }

    @Test
    void replayedTap_onAcknowledgedEscalation_doesNotNotifyOwnerAgain() {
        final UUID logId = UUID.randomUUID();
        when(nudgeAckService.acknowledge(eq(logId), any())).thenReturn(new AckResult.AlreadyAcknowledged());

        final Optional<String> toast = handler.handle(callback("nudge:ack:" + logId));

        assertThat(toast).get(STRING).contains("already acknowledged");
        verify(gateway, never()).sendMarkdown(anyLong(), any(), any());
    }

    @Test
    void unknownLog_isClaimedWithToast_butNotifiesNoOne() {
        final UUID logId = UUID.randomUUID();
        when(nudgeAckService.acknowledge(eq(logId), any())).thenReturn(new AckResult.NotFound());

        final Optional<String> toast = handler.handle(callback("nudge:ack:" + logId));

        assertThat(toast).get(STRING).contains("no longer valid");
        verify(gateway, never()).sendMarkdown(anyLong(), any(), any());
    }

    @Test
    void malformedLogId_isClaimedWithToast_butTouchesNoService() {
        final Optional<String> toast = handler.handle(callback("nudge:ack:not-a-uuid"));

        assertThat(toast).contains("Unknown nudge");
        verifyNoInteractions(nudgeAckService, gateway);
    }

    @Test
    void unrelatedCallbackData_isLeftUnclaimed() {
        final Optional<String> toast = handler.handle(callback("task:done:42"));

        assertThat(toast).isEmpty();
        verifyNoInteractions(nudgeAckService, gateway);
    }

    @Test
    void nullCallbackData_isLeftUnclaimed() {
        final Optional<String> toast = handler.handle(callback(null));

        assertThat(toast).isEmpty();
        verifyNoInteractions(nudgeAckService, gateway);
    }

    private User owner() {
        final User owner = User.create(OWNER_CHAT_ID, "alice", ZoneId.of("Europe/Kyiv"), "uk");
        owner.setId(UUID.randomUUID());
        return owner;
    }

    private CallbackQuery callback(final String data) {
        final CallbackQuery query = new CallbackQuery();
        query.setId("cb-id");
        query.setData(data);
        return query;
    }
}
