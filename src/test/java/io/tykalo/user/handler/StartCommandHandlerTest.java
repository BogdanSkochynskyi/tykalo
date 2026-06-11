package io.tykalo.user.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.tykalo.nudger.AcceptResult;
import io.tykalo.nudger.NudgeInvite;
import io.tykalo.nudger.Nudger;
import io.tykalo.nudger.NudgerPromptService;
import io.tykalo.nudger.NudgerService;
import io.tykalo.onboarding.OnboardingService;
import io.tykalo.telegram.TelegramUpdateFixtures;
import io.tykalo.user.User;
import io.tykalo.user.UserService;
import io.tykalo.user.UserService.Registration;
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
    @Mock
    private NudgerPromptService promptService;
    @Mock
    private OnboardingService onboardingService;

    @InjectMocks
    private StartCommandHandler handler;

    @Test
    void start_beginsOnboarding_andStaysSilent_forNewUserWithoutInvite() {
        // Arrange
        final Update update = TelegramUpdateFixtures.command("/start", 42L, "bob", "uk");
        final User user = User.create(42L, "bob", ZoneId.of("Europe/Kyiv"), "uk");
        when(userService.register(update)).thenReturn(new Registration(user, true));

        // Act
        final String reply = handler.start(update);

        // Assert
        verify(onboardingService).begin(user);
        assertThat(reply).isNull();
        verifyNoInteractions(nudgerService);
    }

    @Test
    void start_greetsReturningUser_withoutOnboarding() {
        // Arrange
        final Update update = TelegramUpdateFixtures.command("/start", 42L, "bob", "uk");
        final User user = User.create(42L, "bob", ZoneId.of("Europe/Kyiv"), "uk");
        when(userService.register(update)).thenReturn(new Registration(user, false));

        // Act
        final String reply = handler.start(update);

        // Assert
        assertThat(reply)
                .contains("Tykalo")
                .contains("@bob")
                .contains("Europe/Kyiv");
        verifyNoInteractions(onboardingService);
        verifyNoInteractions(nudgerService);
    }

    @Test
    void start_greetsGenerically_whenUsernameMissing() {
        final Update update = TelegramUpdateFixtures.command("/start", 42L, null, "uk");
        final User user = User.create(42L, null, ZoneId.of("Europe/Kyiv"), "uk");
        when(userService.register(update)).thenReturn(new Registration(user, false));

        assertThat(handler.start(update)).contains("Hi, there!");
    }

    @Test
    void start_wiresUpNudgerInvite_andSkipsOnboarding_fromDeepLink() {
        // Arrange
        final UUID ownerId = UUID.randomUUID();
        final Update update = TelegramUpdateFixtures.command(
                "/start " + NudgeInvite.payloadFor(ownerId), 42L, "bob", "uk");
        final User invitee = User.create(42L, "bob", ZoneId.of("Europe/Kyiv"), "uk");
        final User owner = User.create(7L, "alice", ZoneId.of("Europe/Kyiv"), "uk");
        final Nudger nudger = new Nudger();
        when(userService.register(update)).thenReturn(new Registration(invitee, true));
        when(nudgerService.acceptViaDeepLink(eq(invitee), eq(ownerId)))
                .thenReturn(new AcceptResult.Invited(nudger, owner));

        // Act
        final String reply = handler.start(update);

        // Assert
        verify(nudgerService).acceptViaDeepLink(invitee, ownerId);
        verify(promptService).sendConsentPrompt(nudger, invitee, owner);
        verifyNoInteractions(onboardingService);
        assertThat(reply)
                .contains("Welcome to Tykalo")
                .contains("@alice invited you to be their Nudger");
    }

    @Test
    void start_ignoresUnparseableStartPayload() {
        final Update update = TelegramUpdateFixtures.command("/start garbage_payload", 42L, "bob", "uk");
        final User user = User.create(42L, "bob", ZoneId.of("Europe/Kyiv"), "uk");
        when(userService.register(update)).thenReturn(new Registration(user, false));

        assertThat(handler.start(update)).doesNotContain("invited you to be their Nudger");
        verifyNoInteractions(nudgerService);
    }
}
