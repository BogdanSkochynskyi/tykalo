package io.tykalo.list;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ListTagsTest {

    @Test
    void validate_lowercasesAndTrims() {
        assertThat(ListTags.validate("  Work  ")).isEqualTo(new ListTags.Validation.Valid("work"));
    }

    @Test
    void validate_acceptsCyrillicLetters() {
        assertThat(ListTags.validate("Покупки")).isEqualTo(new ListTags.Validation.Valid("покупки"));
    }

    @Test
    void validate_acceptsDigits() {
        assertThat(ListTags.validate("q4")).isEqualTo(new ListTags.Validation.Valid("q4"));
    }

    @Test
    void validate_rejectsBlank() {
        assertThat(ListTags.validate("   ")).isEqualTo(new ListTags.Validation.Invalid(ListTags.Error.EMPTY));
    }

    @Test
    void validate_rejectsNull() {
        assertThat(ListTags.validate(null)).isEqualTo(new ListTags.Validation.Invalid(ListTags.Error.EMPTY));
    }

    @Test
    void validate_rejectsTooLong() {
        final String tooLong = "a".repeat(ListTags.MAX_LENGTH + 1);
        assertThat(ListTags.validate(tooLong)).isEqualTo(new ListTags.Validation.Invalid(ListTags.Error.TOO_LONG));
    }

    @Test
    void validate_acceptsExactlyMaxLength() {
        final String exact = "a".repeat(ListTags.MAX_LENGTH);
        assertThat(ListTags.validate(exact)).isEqualTo(new ListTags.Validation.Valid(exact));
    }

    @Test
    void validate_rejectsSpacesInside() {
        assertThat(ListTags.validate("work home"))
                .isEqualTo(new ListTags.Validation.Invalid(ListTags.Error.INVALID_CHARS));
    }

    @Test
    void validate_rejectsPunctuationAndEmoji() {
        assertThat(ListTags.validate("to-do"))
                .isEqualTo(new ListTags.Validation.Invalid(ListTags.Error.INVALID_CHARS));
        assertThat(ListTags.validate("🔥"))
                .isEqualTo(new ListTags.Validation.Invalid(ListTags.Error.INVALID_CHARS));
    }
}
