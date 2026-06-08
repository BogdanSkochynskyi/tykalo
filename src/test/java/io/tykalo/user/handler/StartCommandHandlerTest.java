package io.tykalo.user.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.tykalo.nudger.AcceptResult;
import io.tykalo.nudger.NudgeInvite;
import io.tykalo.nudger.Nudger;
import io.tykalo.nudger.NudgerService;
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
class StartCommandHandlerTest {

    @Mock
    private UserService userService;
    @Mock
    private NudgerService nudgerService;

    @InjectMocks
    private StartCommandHandler handler;

    @Test
    void start_registersUser_andGreetsByUsername() {
        // Arrange
        final Update update = TelegramUpdateFixtures.command("/start", 42L, "bob", "uk");
        when(userService.findOrCreate(update))
                .thenReturn(User.create(42L, "bob", ZoneId.of("Europe/Kyiv"), "uk"));

        // Act
        final String reply = handler.start(update);

        // Assert
        verify(userService).findOrCreate(update);
        assertThat(reply)
                .contains("Tykalo")
                .contains("@bob")
                .contains("Europe/Kyiv");
        verifyNoInteractions(nudgerService);
    }

    @Test
    void start_greetsGenerically_whenUsernameMissing() {
        final Update update = TelegramUpdateFixtures.command("/start", 42L, null, "uk");
        when(userService.findOrCreate(update))
                .thenReturn(User.create(42L, null, ZoneId.of("Europe/Kyiv"), "uk"));

        assertThat(handler.start(update)).contains("Hi, there!");
    }

    @Test
    void start_wiresUpNudgerInvite_fromDeepLink() {
        // Arrange
        final UUID ownerId = UUID.randomUUID();
        final Update update = TelegramUpdateFixtures.command(
                "/start " + NudgeInvite.payloadFor(ownerId), 42L, "bob", "uk");
        final User invitee = User.create(42L, "bob", ZoneId.of("Europe/Kyiv"), "uk");
        final User owner = User.create(7L, "alice", ZoneId.of("Europe/Kyiv"), "uk");
        when(userService.findOrCreate(update)).thenReturn(invitee);
        when(nudgerService.acceptViaDeepLink(eq(invitee), eq(ownerId)))
                .thenReturn(new AcceptResult.Invited(new Nudger(), owner));

        // Act
        final String reply = handler.start(update);

        // Assert
        verify(nudgerService).acceptViaDeepLink(invitee, ownerId);
        assertThat(reply)
                .contains("Welcome to Tykalo")
                .contains("@alice invited you to be their Nudger");
    }

    @Test
    void start_ignoresUnparseableStartPayload() {
        final Update update = TelegramUpdateFixtures.command("/start garbage_payload", 42L, "bob", "uk");
        when(userService.findOrCreate(update))
                .thenReturn(User.create(42L, "bob", ZoneId.of("Europe/Kyiv"), "uk"));

        assertThat(handler.start(update)).doesNotContain("invited you to be their Nudger");
        verifyNoInteractions(nudgerService);
    }
}
