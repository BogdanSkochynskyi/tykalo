package io.tykalo.onboarding.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.tykalo.onboarding.OnboardingService;
import io.tykalo.telegram.TelegramUpdateFixtures;
import io.tykalo.user.User;
import io.tykalo.user.UserRepository;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;

@ExtendWith(MockitoExtension.class)
class OnboardingCallbackHandlerTest {

    @Mock
    private OnboardingService onboardingService;
    @Mock
    private UserRepository userRepository;

    private OnboardingCallbackHandler handler;

    private final User user = User.create(100L, "bob", ZoneId.of("Europe/Kyiv"), "uk");

    @BeforeEach
    void setUp() {
        handler = new OnboardingCallbackHandler(onboardingService, userRepository);
        lenient().when(userRepository.findByTgChatId(100L)).thenReturn(Optional.of(user));
    }

    @Test
    void handle_ignoresNonOnboardingCallback() {
        final CallbackQuery callback = callback("task:done:abc");

        assertThat(handler.handle(callback)).isEmpty();
        verifyNoInteractions(onboardingService);
    }

    @Test
    void handle_routesGo_toService() {
        when(onboardingService.onGo(user, 1)).thenReturn(Optional.of("Step 1 of 3"));

        assertThat(handler.handle(callback("onb:go"))).contains("Step 1 of 3");
        verify(onboardingService).onGo(user, 1);
    }

    @Test
    void handle_routesCreateList_toService() {
        when(onboardingService.onCreateList(user, 1)).thenReturn(Optional.of("🛒 Created your Shopping list!"));

        assertThat(handler.handle(callback("onb:list")).orElseThrow()).contains("Shopping");
        verify(onboardingService).onCreateList(user, 1);
    }

    @Test
    void handle_routesInvite_toService() {
        when(onboardingService.onInvite(user, 1)).thenReturn(Optional.of("done"));

        handler.handle(callback("onb:invite"));

        verify(onboardingService).onInvite(user, 1);
    }

    @Test
    void handle_routesSkip_toService() {
        when(onboardingService.onSkip(user, 1)).thenReturn(Optional.of("You're all set 🙂"));

        handler.handle(callback("onb:skip"));

        verify(onboardingService).onSkip(user, 1);
    }

    @Test
    void handle_returnsExpiredToast_whenUserUnknown() {
        when(userRepository.findByTgChatId(100L)).thenReturn(Optional.empty());

        assertThat(handler.handle(callback("onb:go")).orElseThrow()).contains("expired");
        verifyNoInteractions(onboardingService);
    }

    private CallbackQuery callback(final String data) {
        return TelegramUpdateFixtures.callbackQuery(data).getCallbackQuery();
    }
}
