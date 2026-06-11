package io.tykalo.onboarding.handler;

import io.tykalo.onboarding.OnboardingService;
import io.tykalo.telegram.CallbackHandler;
import io.tykalo.user.User;
import io.tykalo.user.UserRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.message.MaybeInaccessibleMessage;

/**
 * Handles the onboarding step buttons (TK-172): {@code onb:go}, {@code onb:list}, {@code onb:invite}
 * and {@code onb:skip}. The clicking user is re-resolved from the chat and the evolving message id is
 * taken from the callback, so {@link OnboardingService} can edit the same message in place. Callbacks
 * that are not an {@code onb:} action are left unclaimed for other handlers.
 */
@Component
@RequiredArgsConstructor
public class OnboardingCallbackHandler implements CallbackHandler {

    private final OnboardingService onboardingService;
    private final UserRepository userRepository;

    @Override
    public Optional<String> handle(final CallbackQuery callback) {
        final String data = callback.getData();
        if (data == null || !data.startsWith("onb:")) {
            return Optional.empty();
        }
        final Long chatId = chatIdOf(callback);
        final Integer messageId = messageIdOf(callback);
        if (chatId == null || messageId == null) {
            return Optional.of("This button has expired.");
        }
        final Optional<User> user = userRepository.findByTgChatId(chatId);
        if (user.isEmpty()) {
            return Optional.of("This button has expired.");
        }
        return switch (data) {
            case OnboardingService.GO -> onboardingService.onGo(user.get(), messageId);
            case OnboardingService.CREATE_LIST -> onboardingService.onCreateList(user.get(), messageId);
            case OnboardingService.INVITE -> onboardingService.onInvite(user.get(), messageId);
            case OnboardingService.SKIP -> onboardingService.onSkip(user.get(), messageId);
            default -> Optional.empty();
        };
    }

    private @Nullable Long chatIdOf(final CallbackQuery callback) {
        final MaybeInaccessibleMessage message = callback.getMessage();
        return message == null ? null : message.getChatId();
    }

    private @Nullable Integer messageIdOf(final CallbackQuery callback) {
        final MaybeInaccessibleMessage message = callback.getMessage();
        return message == null ? null : message.getMessageId();
    }
}
