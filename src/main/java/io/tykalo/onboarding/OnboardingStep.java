package io.tykalo.onboarding;

/**
 * The step a user is currently sitting on during the first-{@code /start} onboarding (TK-172). The
 * value is persisted per user in Redis ({@code user:{id}:onboarding_step}); the absence of a value
 * means onboarding is not active (never started, skipped, or finished).
 */
public enum OnboardingStep {

    /** Greeting + the Nudgers concept; awaiting "Let's go" or "Skip". */
    GREETING,

    /** Offer to create a first shopping list; awaiting "Create it" or "Skip". */
    CREATE_LIST,

    /** How to invite Nudgers; awaiting "Invite a friend" or "Finish". */
    ADD_NUDGERS
}
