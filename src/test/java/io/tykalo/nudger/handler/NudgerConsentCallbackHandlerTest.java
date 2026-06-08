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

import io.tykalo.nudger.ConsentResult;
import io.tykalo.nudger.Nudger;
import io.tykalo.nudger.NudgerService;
import io.tykalo.nudger.NudgerStatus;
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
class NudgerConsentCallbackHandlerTest {

    private static final long OWNER_CHAT_ID = 7L;

    @Mock
    private NudgerService nudgerService;
    @Mock
    private TelegramMessageGateway gateway;

    @InjectMocks
    private NudgerConsentCallbackHandler handler;

    @Test
    void accept_flipsPairing_notifiesOwner_andConfirmsToInvitee() {
        // Arrange
        final UUID nudgerId = UUID.randomUUID();
        final User owner = owner();
        when(nudgerService.consent(nudgerId, true))
                .thenReturn(new ConsentResult.Accepted(activeNudger(), owner));

        // Act
        final Optional<String> toast = handler.handle(callback("nudger:accept:" + nudgerId, "bob"));

        // Assert
        assertThat(toast).get(STRING).contains("You're now a Nudger");
        verify(gateway).sendMarkdown(eq(OWNER_CHAT_ID), contains("@bob accepted"), isNull());
    }

    @Test
    void decline_flipsPairing_notifiesOwner_andConfirmsToInvitee() {
        final UUID nudgerId = UUID.randomUUID();
        when(nudgerService.consent(nudgerId, false))
                .thenReturn(new ConsentResult.Declined(rejectedNudger(), owner()));

        final Optional<String> toast = handler.handle(callback("nudger:decline:" + nudgerId, "bob"));

        assertThat(toast).get(STRING).contains("Declined");
        verify(gateway).sendMarkdown(eq(OWNER_CHAT_ID), contains("@bob declined"), isNull());
    }

    @Test
    void unknownInviteeUsername_fallsBackToSomeone_inOwnerNotification() {
        final UUID nudgerId = UUID.randomUUID();
        when(nudgerService.consent(nudgerId, true))
                .thenReturn(new ConsentResult.Accepted(activeNudger(), owner()));

        handler.handle(callback("nudger:accept:" + nudgerId, null));

        verify(gateway).sendMarkdown(eq(OWNER_CHAT_ID), contains("Someone accepted"), isNull());
    }

    @Test
    void replayedTap_onDecidedPairing_doesNotNotifyOwnerAgain() {
        final UUID nudgerId = UUID.randomUUID();
        when(nudgerService.consent(nudgerId, true))
                .thenReturn(new ConsentResult.AlreadyDecided(activeNudger()));

        final Optional<String> toast = handler.handle(callback("nudger:accept:" + nudgerId, "bob"));

        assertThat(toast).get(STRING).contains("already a Nudger");
        verify(gateway, never()).sendMarkdown(anyLong(), any(), any());
    }

    @Test
    void unknownNudger_isClaimedWithToast_butNotifiesNoOne() {
        final UUID nudgerId = UUID.randomUUID();
        when(nudgerService.consent(nudgerId, false)).thenReturn(new ConsentResult.NotFound());

        final Optional<String> toast = handler.handle(callback("nudger:decline:" + nudgerId, "bob"));

        assertThat(toast).get(STRING).contains("no longer valid");
        verify(gateway, never()).sendMarkdown(anyLong(), any(), any());
    }

    @Test
    void malformedNudgerId_isClaimedWithToast_butTouchesNoService() {
        final Optional<String> toast = handler.handle(callback("nudger:accept:not-a-uuid", "bob"));

        assertThat(toast).contains("Unknown invite");
        verifyNoInteractions(nudgerService, gateway);
    }

    @Test
    void unrelatedCallbackData_isLeftUnclaimed() {
        final Optional<String> toast = handler.handle(callback("task:done:42", "bob"));

        assertThat(toast).isEmpty();
        verifyNoInteractions(nudgerService, gateway);
    }

    @Test
    void nullCallbackData_isLeftUnclaimed() {
        final Optional<String> toast = handler.handle(callback(null, "bob"));

        assertThat(toast).isEmpty();
        verifyNoInteractions(nudgerService, gateway);
    }

    private Nudger activeNudger() {
        return nudgerWithStatus(NudgerStatus.ACTIVE);
    }

    private Nudger rejectedNudger() {
        return nudgerWithStatus(NudgerStatus.REJECTED);
    }

    private Nudger nudgerWithStatus(final NudgerStatus status) {
        final Nudger nudger = new Nudger();
        nudger.setId(UUID.randomUUID());
        nudger.setStatus(status);
        return nudger;
    }

    private User owner() {
        final User owner = User.create(OWNER_CHAT_ID, "alice", ZoneId.of("Europe/Kyiv"), "uk");
        owner.setId(UUID.randomUUID());
        return owner;
    }

    private CallbackQuery callback(final String data, final String inviteeUsername) {
        final org.telegram.telegrambots.meta.api.objects.User from =
                new org.telegram.telegrambots.meta.api.objects.User(100L, "Bob", false);
        from.setUserName(inviteeUsername);

        final CallbackQuery query = new CallbackQuery();
        query.setId("cb-id");
        query.setFrom(from);
        query.setData(data);
        return query;
    }
}
