package io.tykalo.onboarding;

import io.tykalo.list.ListRenderer;
import io.tykalo.list.ListService;
import io.tykalo.list.ListType;
import io.tykalo.telegram.TelegramMessageGateway;
import io.tykalo.user.User;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

/**
 * Drives the skip-able 3-step onboarding shown on a user's first {@code /start} (TK-172). One message
 * evolves in place through the steps — greeting + Nudgers concept → create a first shopping list →
 * how to add Nudgers — its buttons routed back to {@code OnboardingCallbackHandler}.
 *
 * <p>The current step is the whole state machine, kept per user in Redis under
 * {@code user:{id}:onboarding_step} (7-day TTL); no stored value means onboarding is inactive. Every
 * transition is guarded by that step, so a replayed or stale button after onboarding finished is a
 * harmless no-op — in particular "Create it" can never produce a second Shopping list.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OnboardingService {

    static final Duration TTL = Duration.ofDays(7);

    public static final String GO = "onb:go";
    public static final String CREATE_LIST = "onb:list";
    public static final String INVITE = "onb:invite";
    public static final String SKIP = "onb:skip";

    private static final String FIRST_LIST_NAME = "Shopping";

    private static final String GREETING_TEXT = """
            👋 Welcome to Tykalo — your personal task & list bot!

            Capture anything as a task, and when you ask, I'll nudge you — and even your trusted \
            contacts (your Nudgers) — until it gets done. Let me show you around in 3 quick steps.""";
    private static final String CREATE_LIST_TEXT = """
            1/3 · Lists hold your tasks. Let's make your first one — a simple Shopping list.

            Tap below and I'll create it for you.""";
    private static final String ADD_NUDGERS_TEXT = """
            2/3 · Nudgers are trusted contacts who gently remind you about overdue tasks.

            Invite one any time with /nudgers add @username. Want to do it now?""";
    private static final String FINISHED_TEXT = """
            3/3 · That's it — you're all set! 🎉

            Add a task by just typing it, or send /help to see everything I can do.""";
    private static final String SKIPPED_TEXT = """
            No problem — onboarding skipped. Add a task by just typing it, or send /help whenever \
            you want the tour.""";

    private final StringRedisTemplate redis;
    private final TelegramMessageGateway gateway;
    private final ListService listService;

    /** Starts onboarding for a freshly registered user, sending the greeting step. */
    public void begin(final User user) {
        setStep(user.getId(), OnboardingStep.GREETING);
        gateway.sendMarkdown(user.getTgChatId(), ListRenderer.escape(GREETING_TEXT),
                twoButtons("Let's go →", GO, "Skip", SKIP));
        log.info("Started onboarding for user id={}", user.getId());
    }

    /** "Let's go" on the greeting → move to the create-list step. */
    public Optional<String> onGo(final User user, final int messageId) {
        if (!isAt(user.getId(), OnboardingStep.GREETING)) {
            return alreadyDone();
        }
        setStep(user.getId(), OnboardingStep.CREATE_LIST);
        edit(user, messageId, CREATE_LIST_TEXT, twoButtons("🛒 Create it", CREATE_LIST, "Skip", SKIP));
        return Optional.of("Step 1 of 3");
    }

    /** "Create it" → create the user's first Shopping list and move to the add-Nudgers step. */
    public Optional<String> onCreateList(final User user, final int messageId) {
        if (!isAt(user.getId(), OnboardingStep.CREATE_LIST)) {
            return alreadyDone();
        }
        listService.createList(user, FIRST_LIST_NAME, ListType.CHECKLIST);
        setStep(user.getId(), OnboardingStep.ADD_NUDGERS);
        edit(user, messageId, ADD_NUDGERS_TEXT, twoButtons("👥 Invite a friend", INVITE, "Finish", SKIP));
        return Optional.of("🛒 Created your Shopping list!");
    }

    /** "Invite a friend" → finish onboarding, leaving the how-to in place. */
    public Optional<String> onInvite(final User user, final int messageId) {
        if (!isAt(user.getId(), OnboardingStep.ADD_NUDGERS)) {
            return alreadyDone();
        }
        clear(user.getId());
        edit(user, messageId, FINISHED_TEXT, null);
        return Optional.of("Invite a Nudger with /nudgers add @username");
    }

    /** "Skip"/"Finish" from any active step → end onboarding. */
    public Optional<String> onSkip(final User user, final int messageId) {
        if (!isActive(user.getId())) {
            return alreadyDone();
        }
        clear(user.getId());
        edit(user, messageId, SKIPPED_TEXT, null);
        return Optional.of("You're all set 🙂");
    }

    private void edit(final User user, final int messageId, final String text,
            final InlineKeyboardMarkup keyboard) {
        gateway.editMarkdown(user.getTgChatId(), messageId, ListRenderer.escape(text), keyboard);
    }

    private boolean isActive(final UUID userId) {
        return currentStep(userId).isPresent();
    }

    private boolean isAt(final UUID userId, final OnboardingStep step) {
        return currentStep(userId).filter(s -> s == step).isPresent();
    }

    private Optional<OnboardingStep> currentStep(final UUID userId) {
        final String value = redis.opsForValue().get(key(userId));
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(OnboardingStep.valueOf(value));
        } catch (final IllegalArgumentException e) {
            log.warn("Unrecognized onboarding step '{}' for user {}; treating as inactive", value, userId);
            return Optional.empty();
        }
    }

    private void setStep(final UUID userId, final OnboardingStep step) {
        redis.opsForValue().set(key(userId), step.name(), TTL);
    }

    private void clear(final UUID userId) {
        redis.delete(key(userId));
    }

    private Optional<String> alreadyDone() {
        return Optional.of("You're all set — send /help to explore 🙂");
    }

    private InlineKeyboardMarkup twoButtons(final String primaryText, final String primaryData,
            final String secondaryText, final String secondaryData) {
        final InlineKeyboardRow row = new InlineKeyboardRow();
        row.add(InlineKeyboardButton.builder().text(primaryText).callbackData(primaryData).build());
        row.add(InlineKeyboardButton.builder().text(secondaryText).callbackData(secondaryData).build());
        return InlineKeyboardMarkup.builder().keyboard(List.of(row)).build();
    }

    private String key(final UUID userId) {
        return "user:" + userId + ":onboarding_step";
    }
}
