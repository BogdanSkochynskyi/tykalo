package io.tykalo.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.tykalo.telegram.conversation.ConversationState;
import io.tykalo.telegram.conversation.ConversationStateService;
import io.tykalo.telegram.conversation.StateHandler;
import io.tykalo.user.User;
import io.tykalo.user.UserService;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.objects.Update;

@ExtendWith(MockitoExtension.class)
class TelegramCommandDispatcherTest {

    static class Handlers {
        @TelegramCommand("/Start")
        public String start(final Update update) {
            return "started";
        }

        @TelegramCommand("/boom")
        public String boom(final Update update) {
            throw new IllegalStateException("kaboom");
        }
    }

    @Mock
    private ConversationStateService conversationState;

    @Mock
    private UserService userService;

    private TelegramCommandDispatcher dispatcher;

    @BeforeEach
    void registerHandlers() {
        dispatcher = new TelegramCommandDispatcher(conversationState, userService);
        dispatcher.postProcessAfterInitialization(new Handlers(), "handlers");
    }

    private UUID stubKnownUser() {
        final UUID userId = UUID.randomUUID();
        final User user = User.create(100L, "tester", ZoneId.of("Europe/Kyiv"), "en");
        user.setId(userId);
        when(userService.find(any(Update.class))).thenReturn(Optional.of(user));
        return userId;
    }

    @Test
    void dispatch_routesCommand_toHandler() {
        assertThat(dispatcher.dispatch(TelegramUpdateFixtures.textMessage("/start")))
                .contains("started");
    }

    @Test
    void dispatch_isCaseInsensitive_andStripsBotMention() {
        assertThat(dispatcher.dispatch(TelegramUpdateFixtures.textMessage("/START@TykaloBot extra args")))
                .contains("started");
    }

    @Test
    void dispatch_returnsEmpty_forUnknownCommand() {
        assertThat(dispatcher.dispatch(TelegramUpdateFixtures.textMessage("/unknown"))).isEmpty();
    }

    @Test
    void dispatch_returnsEmpty_forNonCommandText() {
        assertThat(dispatcher.dispatch(TelegramUpdateFixtures.textMessage("just chatting"))).isEmpty();
    }

    @Test
    void dispatch_returnsEmpty_whenUpdateHasNoMessage() {
        assertThat(dispatcher.dispatch(TelegramUpdateFixtures.withoutMessage())).isEmpty();
    }

    @Test
    void dispatch_propagatesHandlerException() {
        final Update update = TelegramUpdateFixtures.textMessage("/boom");
        assertThatThrownBy(() -> dispatcher.dispatch(update))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("kaboom");
    }

    @Test
    void dispatch_routesNonCommandText_toMessageHandler() {
        final AtomicReference<Update> seen = new AtomicReference<>();
        dispatcher.postProcessAfterInitialization((MessageHandler) update -> {
            seen.set(update);
            return Optional.of("bulk-added");
        }, "messageHandler");

        final Update update = TelegramUpdateFixtures.textMessage("milk\nbread");
        assertThat(dispatcher.dispatch(update)).contains("bulk-added");
        assertThat(seen.get()).isSameAs(update);
    }

    @Test
    void dispatch_skipsMessageHandlers_forCommands() {
        final AtomicReference<Boolean> called = new AtomicReference<>(false);
        dispatcher.postProcessAfterInitialization((MessageHandler) update -> {
            called.set(true);
            return Optional.of("should not run");
        }, "messageHandler");

        assertThat(dispatcher.dispatch(TelegramUpdateFixtures.textMessage("/start"))).contains("started");
        assertThat(called.get()).isFalse();
    }

    @Test
    void dispatch_returnsEmpty_whenNoMessageHandlerClaimsText() {
        dispatcher.postProcessAfterInitialization((MessageHandler) update -> Optional.empty(), "messageHandler");

        assertThat(dispatcher.dispatch(TelegramUpdateFixtures.textMessage("unclaimed"))).isEmpty();
    }

    @Test
    void dispatchCallback_routesToFirstClaimingHandler() {
        final AtomicReference<String> seenData = new AtomicReference<>();
        dispatcher.postProcessAfterInitialization((CallbackHandler) callback -> {
            seenData.set(callback.getData());
            return Optional.of("Done!");
        }, "callbackHandler");

        final Update update = TelegramUpdateFixtures.callbackQuery("task:done:abc");
        assertThat(dispatcher.dispatchCallback(update)).contains("Done!");
        assertThat(seenData.get()).isEqualTo("task:done:abc");
    }

    @Test
    void dispatchCallback_returnsEmpty_whenNoHandlerClaimsCallback() {
        dispatcher.postProcessAfterInitialization((CallbackHandler) callback -> Optional.empty(), "callbackHandler");

        assertThat(dispatcher.dispatchCallback(TelegramUpdateFixtures.callbackQuery("noise"))).isEmpty();
    }

    @Test
    void dispatchCallback_returnsEmpty_whenUpdateHasNoCallbackQuery() {
        assertThat(dispatcher.dispatchCallback(TelegramUpdateFixtures.textMessage("plain"))).isEmpty();
    }

    @Test
    void register_rejectsDuplicateCommand() {
        class Duplicate {
            @TelegramCommand("/start")
            public String alsoStart(final Update update) {
                return "dup";
            }
        }
        assertThatThrownBy(() -> dispatcher.postProcessAfterInitialization(new Duplicate(), "dup"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate");
    }

    @Test
    void register_rejectsInvalidSignature() {
        class BadHandler {
            @TelegramCommand("/bad")
            public void noReturn(final Update update) {
            }
        }
        assertThatThrownBy(() -> dispatcher.postProcessAfterInitialization(new BadHandler(), "bad"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("signature");
    }

    @Test
    void dispatch_routesNonCommandText_toStateHandler_whenStateExpectsInput() {
        final UUID userId = stubKnownUser();
        final ConversationState state = new ConversationState.AddingItems(UUID.randomUUID());
        when(conversationState.getState(userId)).thenReturn(state);

        final AtomicReference<ConversationState> seen = new AtomicReference<>();
        dispatcher.postProcessAfterInitialization(new StateHandler() {
            @Override
            public boolean canHandle(final ConversationState s) {
                return s instanceof ConversationState.AddingItems;
            }

            @Override
            public Optional<String> handle(final Update update, final ConversationState s) {
                seen.set(s);
                return Optional.of("item added");
            }
        }, "stateHandler");

        assertThat(dispatcher.dispatch(TelegramUpdateFixtures.textMessage("milk"))).contains("item added");
        assertThat(seen.get()).isEqualTo(state);
    }

    @Test
    void dispatch_fallsThroughToMessageHandlers_whenNoStateHandlerClaimsInput() {
        final UUID userId = stubKnownUser();
        when(conversationState.getState(userId)).thenReturn(new ConversationState.AddingItems(UUID.randomUUID()));

        final AtomicReference<Boolean> messageHandlerCalled = new AtomicReference<>(false);
        dispatcher.postProcessAfterInitialization((MessageHandler) update -> {
            messageHandlerCalled.set(true);
            return Optional.of("bulk");
        }, "messageHandler");

        assertThat(dispatcher.dispatch(TelegramUpdateFixtures.textMessage("milk"))).contains("bulk");
        assertThat(messageHandlerCalled.get()).isTrue();
    }

    @Test
    void dispatch_doesNotConsultStateHandlers_whenStateIsNotInputExpecting() {
        final UUID userId = stubKnownUser();
        when(conversationState.getState(userId)).thenReturn(new ConversationState.MainMenu());

        final AtomicReference<Boolean> stateHandlerConsulted = new AtomicReference<>(false);
        dispatcher.postProcessAfterInitialization(new StateHandler() {
            @Override
            public boolean canHandle(final ConversationState s) {
                stateHandlerConsulted.set(true);
                return true;
            }

            @Override
            public Optional<String> handle(final Update update, final ConversationState s) {
                return Optional.of("from-state");
            }
        }, "stateHandler");
        dispatcher.postProcessAfterInitialization((MessageHandler) update -> Optional.of("bulk"), "messageHandler");

        assertThat(dispatcher.dispatch(TelegramUpdateFixtures.textMessage("hi"))).contains("bulk");
        assertThat(stateHandlerConsulted.get()).isFalse();
    }

    @Test
    void dispatch_command_exitsInputExpectingState_thenRunsCommandNormally() {
        final UUID userId = stubKnownUser();
        when(conversationState.getState(userId)).thenReturn(new ConversationState.AddingItems(UUID.randomUUID()));

        assertThat(dispatcher.dispatch(TelegramUpdateFixtures.textMessage("/start"))).contains("started");
        verify(conversationState).clearState(userId);
    }

    @Test
    void dispatch_command_leavesNavigationStateIntact() {
        final UUID userId = stubKnownUser();
        when(conversationState.getState(userId)).thenReturn(new ConversationState.MainMenu());

        assertThat(dispatcher.dispatch(TelegramUpdateFixtures.textMessage("/start"))).contains("started");
        verify(conversationState, never()).clearState(any());
    }

    @Test
    void dispatch_returnsEmpty_whenHandlerReturnsNull() {
        final TelegramCommandDispatcher local = new TelegramCommandDispatcher(conversationState, userService);
        local.postProcessAfterInitialization(new Object() {
            @TelegramCommand("/silent")
            public String silent(final Update update) {
                return null;
            }
        }, "silent");

        final Optional<String> reply = local.dispatch(TelegramUpdateFixtures.textMessage("/silent"));

        assertThat(reply).isEmpty();
    }
}
