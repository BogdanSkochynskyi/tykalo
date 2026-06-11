package io.tykalo.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.tykalo.list.ListService;
import io.tykalo.list.ListType;
import io.tykalo.telegram.TelegramMessageGateway;
import io.tykalo.user.User;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

@ExtendWith(MockitoExtension.class)
class OnboardingServiceTest {

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private TelegramMessageGateway gateway;
    @Mock
    private ListService listService;

    private OnboardingService service;

    private final UUID userId = UUID.randomUUID();
    private User user;

    @BeforeEach
    void setUp() {
        service = new OnboardingService(redis, gateway, listService);
        user = User.create(42L, "bob", ZoneId.of("Europe/Kyiv"), "uk");
        user.setId(userId);
        lenient().when(redis.opsForValue()).thenReturn(valueOps);
    }

    @Test
    void begin_storesGreetingStep_andSendsConceptMessageWithButtons() {
        // Act
        service.begin(user);

        // Assert
        verify(valueOps).set(key(), OnboardingStep.GREETING.name(), Duration.ofDays(7));
        verify(gateway).sendMarkdown(eq(42L), any(String.class), any(InlineKeyboardMarkup.class));
    }

    @Test
    void onGo_advancesToCreateList_whenAtGreeting() {
        // Arrange
        when(valueOps.get(key())).thenReturn(OnboardingStep.GREETING.name());

        // Act
        final Optional<String> toast = service.onGo(user, 7);

        // Assert
        assertThat(toast).isPresent();
        verify(valueOps).set(key(), OnboardingStep.CREATE_LIST.name(), Duration.ofDays(7));
        verify(gateway).editMarkdown(eq(42L), eq(7), any(String.class), any(InlineKeyboardMarkup.class));
    }

    @Test
    void onGo_isNoOp_whenOnboardingNotActive() {
        when(valueOps.get(key())).thenReturn(null);

        final Optional<String> toast = service.onGo(user, 7);

        assertThat(toast).isPresent();
        verify(valueOps, never()).set(any(), any(), any(Duration.class));
        verifyNoInteractions(gateway);
    }

    @Test
    void onCreateList_createsShoppingChecklist_andAdvances_whenAtCreateListStep() {
        // Arrange
        when(valueOps.get(key())).thenReturn(OnboardingStep.CREATE_LIST.name());

        // Act
        final Optional<String> toast = service.onCreateList(user, 7);

        // Assert
        assertThat(toast).isPresent();
        verify(listService).createList(user, "Shopping", ListType.CHECKLIST);
        verify(valueOps).set(key(), OnboardingStep.ADD_NUDGERS.name(), Duration.ofDays(7));
        verify(gateway).editMarkdown(eq(42L), eq(7), any(String.class), any(InlineKeyboardMarkup.class));
    }

    @Test
    void onCreateList_doesNotCreateSecondList_whenStepAlreadyPassed() {
        when(valueOps.get(key())).thenReturn(OnboardingStep.ADD_NUDGERS.name());

        service.onCreateList(user, 7);

        verifyNoInteractions(listService);
        verifyNoInteractions(gateway);
    }

    @Test
    void onInvite_finishesOnboarding_whenAtAddNudgersStep() {
        // Arrange
        when(valueOps.get(key())).thenReturn(OnboardingStep.ADD_NUDGERS.name());

        // Act
        final Optional<String> toast = service.onInvite(user, 7);

        // Assert
        assertThat(toast).isPresent();
        verify(redis).delete(key());
        verify(gateway).editMarkdown(eq(42L), eq(7), any(String.class), isNull());
    }

    @Test
    void onSkip_endsOnboarding_fromAnyActiveStep() {
        when(valueOps.get(key())).thenReturn(OnboardingStep.CREATE_LIST.name());

        final Optional<String> toast = service.onSkip(user, 7);

        assertThat(toast).isPresent();
        verify(redis).delete(key());
        verify(gateway).editMarkdown(eq(42L), eq(7), any(String.class), isNull());
    }

    @Test
    void onSkip_isNoOp_whenOnboardingNotActive() {
        when(valueOps.get(key())).thenReturn(null);

        service.onSkip(user, 7);

        verify(redis, never()).delete(any(String.class));
        verifyNoInteractions(gateway);
    }

    @Test
    void unrecognizedStoredStep_isTreatedAsInactive() {
        when(valueOps.get(key())).thenReturn("GARBAGE");

        service.onGo(user, 7);

        verify(valueOps, never()).set(any(), any(), any(Duration.class));
        verifyNoInteractions(gateway);
    }

    private String key() {
        return "user:" + userId + ":onboarding_step";
    }
}
