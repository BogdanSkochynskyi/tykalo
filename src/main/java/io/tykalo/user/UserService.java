package io.tykalo.user;

import java.time.LocalTime;
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

    /** The outcome of {@link #register(Update)}: the user, plus whether this contact created them. */
    public record Registration(User user, boolean created) {}

    /**
     * Returns the user behind the given update, creating one on first contact, and reports whether
     * the user was newly created. The {@code created} flag lets {@code /start} run onboarding only
     * on a genuine first contact (TK-172). The Telegram chat id is the stable identity key.
     */
    @Transactional
    public Registration register(final Update update) {
        final Message message = update.getMessage();
        if (message == null || message.getFrom() == null) {
            throw new IllegalArgumentException("Update has no message sender to identify a user");
        }
        return userRepository.findByTgChatId(message.getChatId())
                .map(existing -> new Registration(existing, false))
                .orElseGet(() -> new Registration(create(message), true));
    }

    /**
     * Returns the user behind the given update, creating one on first contact.
     * The Telegram chat id is the stable identity key.
     */
    @Transactional
    public User findOrCreate(final Update update) {
        return register(update).user();
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

    /**
     * Persists a new quiet-hours window for the user. Callers validate the bounds at the boundary;
     * this method just stores them (the user is merged, as it arrives detached).
     */
    @Transactional
    public User updateQuietHours(final User user, final LocalTime start, final LocalTime end) {
        user.setQuietHoursStart(start);
        user.setQuietHoursEnd(end);
        final User saved = userRepository.save(user);
        log.info("Updated quiet hours of user id={} to {}-{}", saved.getId(), start, end);
        return saved;
    }

    /**
     * Persists the hour (local wall-clock, 0–23) at which the user gets their morning digest. Callers
     * validate the range at the boundary; this method just stores it (the user is merged, as it
     * arrives detached).
     */
    @Transactional
    public User updateDigestHour(final User user, final int hour) {
        user.setDigestHour(hour);
        final User saved = userRepository.save(user);
        log.info("Updated digest hour of user id={} to {}", saved.getId(), hour);
        return saved;
    }

    /** Turns the morning digest off for the user (clears the digest hour). */
    @Transactional
    public User disableDigest(final User user) {
        user.setDigestHour(null);
        final User saved = userRepository.save(user);
        log.info("Disabled morning digest of user id={}", saved.getId());
        return saved;
    }

    /** Clears the user's quiet-hours window, so no period is suppressed. */
    @Transactional
    public User disableQuietHours(final User user) {
        user.setQuietHoursStart(null);
        user.setQuietHoursEnd(null);
        final User saved = userRepository.save(user);
        log.info("Disabled quiet hours of user id={}", saved.getId());
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
