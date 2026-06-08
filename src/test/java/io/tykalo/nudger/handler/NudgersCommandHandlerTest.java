package io.tykalo.nudger.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.tykalo.nudger.InviteResult;
import io.tykalo.nudger.Nudger;
import io.tykalo.nudger.NudgerActionResult;
import io.tykalo.nudger.NudgerPromptService;
import io.tykalo.nudger.NudgerService;
import io.tykalo.nudger.NudgerStatus;
import io.tykalo.nudger.NudgerSummary;
import io.tykalo.telegram.TelegramBotProperties;
import io.tykalo.telegram.TelegramUpdateFixtures;
import io.tykalo.user.User;
import io.tykalo.user.UserService;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.objects.Update;

@ExtendWith(MockitoExtension.class)
class NudgersCommandHandlerTest {

    @Mock
    private UserService userService;
    @Mock
    private NudgerService nudgerService;
    @Mock
    private NudgerPromptService promptService;
    @Mock
    private TelegramBotProperties botProperties;

    @InjectMocks
    private NudgersCommandHandler handler;

    @Test
    void add_createsPairing_andSendsConsentPromptToRegisteredInvitee() {
        // Arrange
        final Update update = TelegramUpdateFixtures.command("/nudgers add @helper", 1L, "owner", "en");
        final User owner = user(1L, "owner");
        final User invitee = user(2L, "helper");
        final Nudger nudger = Nudger.invite(owner, invitee);
        when(userService.findOrCreate(update)).thenReturn(owner);
        when(nudgerService.invite(owner, "@helper"))
                .thenReturn(new InviteResult.Invited(nudger, invitee));

        // Act
        final String reply = handler.nudgers(update);

        // Assert
        assertThat(reply).contains("Invited @helper");
        verify(promptService).sendConsentPrompt(nudger, invitee, owner);
    }

    @Test
    void add_returnsDeepLink_whenInviteeNotRegistered() {
        // Arrange
        final Update update = TelegramUpdateFixtures.command("/nudgers add @ghost", 1L, "owner", "en");
        final User owner = user(1L, "owner");
        when(userService.findOrCreate(update)).thenReturn(owner);
        when(nudgerService.invite(owner, "@ghost")).thenReturn(new InviteResult.NotRegistered("ghost"));
        when(botProperties.getUsername()).thenReturn("TykaloBot");

        // Act
        final String reply = handler.nudgers(update);

        // Assert
        assertThat(reply)
                .contains("@ghost isn't on Tykalo yet")
                .contains("https://t.me/TykaloBot?start=nudge_invite_");
        verify(promptService, never()).sendConsentPrompt(any(), any(), any());
    }

    @Test
    void add_rejectsSelfInvite() {
        final Update update = TelegramUpdateFixtures.command("/nudgers add @owner", 1L, "owner", "en");
        final User owner = user(1L, "owner");
        when(userService.findOrCreate(update)).thenReturn(owner);
        when(nudgerService.invite(owner, "@owner")).thenReturn(new InviteResult.SelfInvite());

        assertThat(handler.nudgers(update)).contains("can't add yourself");
    }

    @Test
    void add_reportsExistingPairing() {
        final Update update = TelegramUpdateFixtures.command("/nudgers add @helper", 1L, "owner", "en");
        final User owner = user(1L, "owner");
        final User invitee = user(2L, "helper");
        when(userService.findOrCreate(update)).thenReturn(owner);
        when(nudgerService.invite(owner, "@helper"))
                .thenReturn(new InviteResult.AlreadyInvited(Nudger.invite(owner, invitee), invitee));

        assertThat(handler.nudgers(update)).contains("already your Nudger").contains("PENDING");
    }

    @Test
    void add_withoutUsername_returnsUsage() {
        final Update update = TelegramUpdateFixtures.command("/nudgers add", 1L, "owner", "en");

        assertThat(handler.nudgers(update)).startsWith("Usage:").contains("/nudgers add @username");
    }

    @Test
    void unknownSubcommand_returnsUsage() {
        final Update update = TelegramUpdateFixtures.command("/nudgers wat", 1L, "owner", "en");

        assertThat(handler.nudgers(update)).startsWith("Usage:").contains("/nudgers add @username");
    }

    @Test
    void list_rendersActiveNudgersWithKarma() {
        final Update update = TelegramUpdateFixtures.command("/nudgers list", 1L, "owner", "en");
        final User owner = user(1L, "owner");
        when(userService.findOrCreate(update)).thenReturn(owner);
        when(nudgerService.listActive(owner))
                .thenReturn(List.of(new NudgerSummary("alice", 5), new NudgerSummary("bob", 2)));

        assertThat(handler.nudgers(update))
                .contains("@alice — karma 5")
                .contains("@bob — karma 2");
    }

    @Test
    void list_whenNoNudgers_invitesToAddOne() {
        final Update update = TelegramUpdateFixtures.command("/nudgers list", 1L, "owner", "en");
        final User owner = user(1L, "owner");
        when(userService.findOrCreate(update)).thenReturn(owner);
        when(nudgerService.listActive(owner)).thenReturn(List.of());

        assertThat(handler.nudgers(update)).contains("no active Nudgers");
    }

    @Test
    void bareNudgers_defaultsToList() {
        final Update update = TelegramUpdateFixtures.command("/nudgers", 1L, "owner", "en");
        final User owner = user(1L, "owner");
        when(userService.findOrCreate(update)).thenReturn(owner);
        when(nudgerService.listActive(owner)).thenReturn(List.of());

        assertThat(handler.nudgers(update)).contains("no active Nudgers");
    }

    @Test
    void remove_withoutConfirm_asksForConfirmation_withoutDeleting() {
        final Update update = TelegramUpdateFixtures.command("/nudgers remove @helper", 1L, "owner", "en");
        final User owner = user(1L, "owner");
        final User invitee = user(2L, "helper");
        when(userService.findOrCreate(update)).thenReturn(owner);
        when(nudgerService.find(owner, "@helper"))
                .thenReturn(new NudgerActionResult.Ok(Nudger.invite(owner, invitee), invitee));

        assertThat(handler.nudgers(update))
                .contains("Remove @helper")
                .contains("/nudgers remove @helper confirm");
        verify(nudgerService, never()).remove(any(), any());
    }

    @Test
    void remove_withConfirm_deletesPairing() {
        final Update update = TelegramUpdateFixtures.command("/nudgers remove @helper confirm", 1L, "owner", "en");
        final User owner = user(1L, "owner");
        final User invitee = user(2L, "helper");
        when(userService.findOrCreate(update)).thenReturn(owner);
        when(nudgerService.remove(owner, "@helper"))
                .thenReturn(new NudgerActionResult.Ok(Nudger.invite(owner, invitee), invitee));

        assertThat(handler.nudgers(update)).contains("Removed @helper");
        verify(nudgerService).remove(owner, "@helper");
    }

    @Test
    void remove_reportsNotANudger() {
        final Update update = TelegramUpdateFixtures.command("/nudgers remove @stranger", 1L, "owner", "en");
        final User owner = user(1L, "owner");
        when(userService.findOrCreate(update)).thenReturn(owner);
        when(nudgerService.find(owner, "@stranger"))
                .thenReturn(new NudgerActionResult.NotANudger("stranger"));

        assertThat(handler.nudgers(update)).contains("isn't one of your Nudgers");
    }

    @Test
    void pause_deactivatesActiveNudger() {
        final Update update = TelegramUpdateFixtures.command("/nudgers pause @helper", 1L, "owner", "en");
        final User owner = user(1L, "owner");
        final User invitee = user(2L, "helper");
        when(userService.findOrCreate(update)).thenReturn(owner);
        when(nudgerService.pause(owner, "@helper"))
                .thenReturn(new NudgerActionResult.Ok(Nudger.invite(owner, invitee), invitee));

        assertThat(handler.nudgers(update)).contains("Paused @helper");
    }

    @Test
    void pause_whenAlreadyPaused_saysSo() {
        final Update update = TelegramUpdateFixtures.command("/nudgers pause @helper", 1L, "owner", "en");
        final User owner = user(1L, "owner");
        final User invitee = user(2L, "helper");
        final Nudger paused = Nudger.invite(owner, invitee);
        paused.setStatus(NudgerStatus.PAUSED);
        when(userService.findOrCreate(update)).thenReturn(owner);
        when(nudgerService.pause(owner, "@helper"))
                .thenReturn(new NudgerActionResult.Unchanged(paused, invitee));

        assertThat(handler.nudgers(update)).contains("already paused");
    }

    @Test
    void resume_reactivatesPausedNudger() {
        final Update update = TelegramUpdateFixtures.command("/nudgers resume @helper", 1L, "owner", "en");
        final User owner = user(1L, "owner");
        final User invitee = user(2L, "helper");
        when(userService.findOrCreate(update)).thenReturn(owner);
        when(nudgerService.resume(owner, "@helper"))
                .thenReturn(new NudgerActionResult.Ok(Nudger.invite(owner, invitee), invitee));

        assertThat(handler.nudgers(update)).contains("Resumed @helper");
    }

    @Test
    void remove_withoutUsername_returnsUsage() {
        final Update update = TelegramUpdateFixtures.command("/nudgers remove", 1L, "owner", "en");

        assertThat(handler.nudgers(update)).isEqualTo("Usage: /nudgers remove @username");
    }

    private User user(final long tgChatId, final String username) {
        final User user = User.create(tgChatId, username, ZoneId.of("Europe/Kyiv"), "uk");
        user.setId(UUID.randomUUID());
        return user;
    }
}
