package io.tykalo.user;

import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {

    private final UserRepository userRepository;
    private final TimezoneResolver timezoneResolver;
    private final ApplicationEventPublisher events;

    /**
     * Returns the user behind the given update, creating one on first contact.
     * The Telegram chat id is the stable identity key.
     */
    @Transactional
    public User findOrCreate(final Update update) {
        final Message message = update.getMessage();
        if (message == null || message.getFrom() == null) {
            throw new IllegalArgumentException("Update has no message sender to identify a user");
        }
        return userRepository.findByTgChatId(message.getChatId())
                .orElseGet(() -> create(message));
    }

    /**
     * Persists a new timezone for the user. Callers validate the {@link ZoneId} at the
     * boundary; this method just stores it (the user is merged, as it arrives detached).
     */
    @Transactional
    public User updateTimezone(final User user, final ZoneId timezone) {
        user.setTimezone(timezone);
        final User saved = userRepository.save(user);
        log.info("Updated timezone of user id={} to {}", saved.getId(), timezone);
        return saved;
    }

    private User create(final Message message) {
        final String languageCode = message.getFrom().getLanguageCode();
        final ZoneId timezone = timezoneResolver.resolve(languageCode);
        final User user = userRepository.save(
                User.create(message.getChatId(), message.getFrom().getUserName(), timezone, languageCode));
        log.info("Created new user id={} tgChatId={} timezone={}", user.getId(), user.getTgChatId(), timezone);
        events.publishEvent(new UserCreatedEvent(user));
        return user;
    }
}
