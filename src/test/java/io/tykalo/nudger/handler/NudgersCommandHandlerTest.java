package io.tykalo.nudger.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.tykalo.nudger.InviteResult;
import io.tykalo.nudger.Nudger;
import io.tykalo.nudger.NudgerPromptService;
import io.tykalo.nudger.NudgerService;
import io.tykalo.telegram.TelegramBotProperties;
import io.tykalo.telegram.TelegramUpdateFixtures;
import io.tykalo.user.User;
import io.tykalo.user.UserService;
import java.time.ZoneId;
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

        assertThat(handler.nudgers(update)).isEqualTo("Usage: /nudgers add @username");
    }

    @Test
    void unknownSubcommand_returnsUsage() {
        final Update update = TelegramUpdateFixtures.command("/nudgers wat", 1L, "owner", "en");

        assertThat(handler.nudgers(update)).isEqualTo("Usage: /nudgers add @username");
    }

    private User user(final long tgChatId, final String username) {
        final User user = User.create(tgChatId, username, ZoneId.of("Europe/Kyiv"), "uk");
        user.setId(UUID.randomUUID());
        return user;
    }
}
