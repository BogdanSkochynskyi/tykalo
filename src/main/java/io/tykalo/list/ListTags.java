package io.tykalo.list;

import java.util.List;
import java.util.Locale;

/**
 * Validation and normalization for list tags (TK-259), kept separate from {@link ListService} so the
 * same rules can back both the tags UI and pending-item matching on new-list creation (TK-258).
 *
 * <p>A tag is letters and digits only — Latin or Cyrillic, via {@link Character#isLetterOrDigit} so a
 * Ukrainian tag works as well as an English one — at most {@link #MAX_LENGTH} characters, stored
 * lowercased and trimmed. {@link #validate(String)} returns a sealed {@link Validation} so callers
 * pattern-match the normalized value or the specific rejection reason rather than juggling nulls.
 */
public final class ListTags {

    /** Maximum tag length, in characters, after trimming. */
    public static final int MAX_LENGTH = 30;

    /** Common tags offered as one-tap quick-adds; the order is the on-screen order. */
    public static final List<String> SUGGESTIONS = List.of("shopping", "groceries", "work", "home", "health", "bills");

    private ListTags() {
    }

    /** The reason a raw tag was rejected, mapped to a user-facing message by the UI layer. */
    public enum Error {
        EMPTY, TOO_LONG, INVALID_CHARS
    }

    /** The outcome of {@link #validate(String)}: a normalized tag, or the reason it was rejected. */
    public sealed interface Validation {

        /** The trimmed, lowercased tag, ready to store. */
        record Valid(String tag) implements Validation {
        }

        /** The raw input broke a rule. */
        record Invalid(Error error) implements Validation {
        }
    }

    /** Trims, length- and charset-checks, then lowercases. Never throws — invalid input is a result. */
    public static Validation validate(final String raw) {
        if (raw == null) {
            return new Validation.Invalid(Error.EMPTY);
        }
        final String trimmed = raw.strip();
        if (trimmed.isEmpty()) {
            return new Validation.Invalid(Error.EMPTY);
        }
        if (trimmed.length() > MAX_LENGTH) {
            return new Validation.Invalid(Error.TOO_LONG);
        }
        if (!trimmed.chars().allMatch(Character::isLetterOrDigit)) {
            return new Validation.Invalid(Error.INVALID_CHARS);
        }
        return new Validation.Valid(trimmed.toLowerCase(Locale.ROOT));
    }
}
