package io.tykalo.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.objects.Update;

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

    private final TelegramCommandDispatcher dispatcher = new TelegramCommandDispatcher();

    @BeforeEach
    void registerHandlers() {
        dispatcher.postProcessAfterInitialization(new Handlers(), "handlers");
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
    void dispatch_returnsEmpty_whenHandlerReturnsNull() {
        final TelegramCommandDispatcher local = new TelegramCommandDispatcher();
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
