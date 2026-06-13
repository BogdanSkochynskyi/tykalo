package io.tykalo.telegram;

import io.tykalo.telegram.conversation.ConversationState;
import io.tykalo.telegram.conversation.ConversationStateService;
import io.tykalo.telegram.conversation.StateHandler;
import io.tykalo.user.User;
import io.tykalo.user.UserService;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.ReflectionUtils;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;

/**
 * Discovers {@link TelegramCommand}-annotated methods on every Spring bean at startup
 * and routes incoming updates to them by command. Pure routing — it never touches the
 * Telegram API, so it is cheap to instantiate and lives in every application context.
 *
 * <p>Before the ordinary command/message handlers it consults the user's
 * {@link ConversationState} (TK-187): a plain-text message in an input-expecting state is routed to
 * the matching {@link StateHandler} instead of the message handlers, and a command issued in such a
 * state first exits it, then runs normally. Resolving the user and reading the state are deferred
 * collaborators — injected {@code @Lazy} because this bean is a {@link BeanPostProcessor} created
 * before the Redis/JPA infrastructure those collaborators need.
 */
@Component
@Slf4j
public class TelegramCommandDispatcher implements BeanPostProcessor {

    private final Map<String, CommandHandler> handlers = new HashMap<>();
    private final List<MessageHandler> messageHandlers = new ArrayList<>();
    private final List<CallbackHandler> callbackHandlers = new ArrayList<>();
    private final List<StateHandler> stateHandlers = new ArrayList<>();

    private final ConversationStateService conversationState;
    private final UserService userService;

    public TelegramCommandDispatcher(@Lazy final ConversationStateService conversationState,
                                     @Lazy final UserService userService) {
        this.conversationState = conversationState;
        this.userService = userService;
    }

    @Override
    public Object postProcessAfterInitialization(final Object bean, final String beanName) {
        final Class<?> targetClass = AopUtils.getTargetClass(bean);
        final Map<Method, TelegramCommand> found = MethodIntrospector.selectMethods(
                targetClass,
                (MethodIntrospector.MetadataLookup<TelegramCommand>) method ->
                        AnnotatedElementUtils.findMergedAnnotation(method, TelegramCommand.class));
        found.forEach((method, annotation) -> register(bean, method, annotation));
        if (bean instanceof MessageHandler messageHandler) {
            messageHandlers.add(messageHandler);
            log.debug("Registered message handler -> {}", bean.getClass().getName());
        }
        if (bean instanceof CallbackHandler callbackHandler) {
            callbackHandlers.add(callbackHandler);
            log.debug("Registered callback handler -> {}", bean.getClass().getName());
        }
        if (bean instanceof StateHandler stateHandler) {
            stateHandlers.add(stateHandler);
            log.debug("Registered state handler -> {}", bean.getClass().getName());
        }
        return bean;
    }

    private void register(final Object bean, final Method method, final TelegramCommand annotation) {
        validateSignature(method);
        final String command = normalize(annotation.value());
        final CommandHandler handler = new CommandHandler(bean, method);
        final CommandHandler existing = handlers.putIfAbsent(command, handler);
        if (existing != null) {
            throw new IllegalStateException("Duplicate @TelegramCommand handler for '" + command
                    + "': " + existing.describe() + " and " + handler.describe());
        }
        ReflectionUtils.makeAccessible(method);
        log.debug("Registered Telegram command '{}' -> {}", command, handler.describe());
    }

    private void validateSignature(final Method method) {
        final boolean valid = method.getParameterCount() == 1
                && method.getParameterTypes()[0].equals(Update.class)
                && method.getReturnType().equals(String.class);
        if (!valid) {
            throw new IllegalStateException(
                    "@TelegramCommand method must have signature 'String handle(Update)': " + method);
        }
    }

    /** Routes an update to its handler, returning the reply text if one was produced. */
    public Optional<String> dispatch(final Update update) {
        final Message message = update.getMessage();
        if (message == null || message.getText() == null) {
            return Optional.empty();
        }
        final String command = extractCommand(message.getText());
        if (command == null) {
            return dispatchText(update);
        }
        exitInputExpectingState(update);
        final CommandHandler handler = handlers.get(command);
        if (handler == null) {
            log.debug("No handler registered for command '{}'", command);
            return Optional.empty();
        }
        return Optional.ofNullable(handler.invoke(update));
    }

    /**
     * Routes a plain-text message. In an input-expecting {@link ConversationState} the first
     * {@link StateHandler} that claims the state owns the message (its result is returned as-is, even
     * when empty); otherwise — including every message in a navigation or idle state — it falls
     * through to the ordinary message handlers.
     */
    private Optional<String> dispatchText(final Update update) {
        final Optional<UUID> userId = currentUserId(update);
        if (userId.isPresent()) {
            final ConversationState state = conversationState.getState(userId.get());
            if (state.expectsTextInput()) {
                for (final StateHandler handler : stateHandlers) {
                    if (handler.canHandle(state)) {
                        return handler.handle(update, state);
                    }
                }
            }
        }
        return dispatchToMessageHandlers(update);
    }

    /** A command breaks out of any input-expecting flow before it runs (a navigation state is left intact). */
    private void exitInputExpectingState(final Update update) {
        final Optional<UUID> userId = currentUserId(update);
        if (userId.isEmpty()) {
            return;
        }
        if (conversationState.getState(userId.get()).expectsTextInput()) {
            conversationState.clearState(userId.get());
            log.debug("Command received while awaiting input; cleared conversation state for user {}", userId.get());
        }
    }

    private Optional<UUID> currentUserId(final Update update) {
        return userService.find(update).map(User::getId);
    }

    /** Consults plain-message handlers in registration order, returning the first non-empty reply. */
    private Optional<String> dispatchToMessageHandlers(final Update update) {
        for (final MessageHandler handler : messageHandlers) {
            final Optional<String> reply = handler.handle(update);
            if (reply.isPresent()) {
                return reply;
            }
        }
        return Optional.empty();
    }

    /**
     * Routes an inline-button click to the callback handlers in registration order, returning the
     * first non-empty toast. Empty when the update carries no callback data or no handler claims it;
     * the caller still answers the callback to dismiss Telegram's loading spinner.
     */
    public Optional<String> dispatchCallback(final Update update) {
        final CallbackQuery query = update.getCallbackQuery();
        if (query == null || query.getData() == null) {
            return Optional.empty();
        }
        for (final CallbackHandler handler : callbackHandlers) {
            final Optional<String> toast = handler.handle(query);
            if (toast.isPresent()) {
                return toast;
            }
        }
        return Optional.empty();
    }

    private @Nullable String extractCommand(final String text) {
        final String firstToken = text.strip().split("\\s+", 2)[0];
        if (!firstToken.startsWith("/")) {
            return null;
        }
        final int atIndex = firstToken.indexOf('@');
        final String command = atIndex >= 0 ? firstToken.substring(0, atIndex) : firstToken;
        return normalize(command);
    }

    private String normalize(final String command) {
        return command.toLowerCase(Locale.ROOT);
    }

    private record CommandHandler(Object bean, Method method) {

        @Nullable String invoke(final Update update) {
            try {
                return (String) method.invoke(bean, update);
            } catch (final IllegalAccessException e) {
                throw new IllegalStateException("Cannot invoke handler " + describe(), e);
            } catch (final InvocationTargetException e) {
                final Throwable cause = e.getTargetException();
                if (cause instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw new IllegalStateException("Handler " + describe() + " failed", cause);
            }
        }

        String describe() {
            return bean.getClass().getName() + "#" + method.getName();
        }
    }
}
