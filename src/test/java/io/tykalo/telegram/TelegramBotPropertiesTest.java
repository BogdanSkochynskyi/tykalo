package io.tykalo.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class TelegramBotPropertiesTest {

    @Test
    void getToken_returnsConfiguredValue() {
        final TelegramBotProperties properties = new TelegramBotProperties("123456:ABC-DEF");

        assertThat(properties.getToken()).isEqualTo("123456:ABC-DEF");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\t"})
    void constructor_throwsWithClearMessage_whenTokenBlank(final String blank) {
        assertThatThrownBy(() -> new TelegramBotProperties(blank))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TELEGRAM_BOT_TOKEN");
    }

    @Test
    void context_failsToStart_whenTokenEmpty() {
        new ApplicationContextRunner()
                .withUserConfiguration(TelegramBotProperties.class)
                .withPropertyValues("telegram.bot.token=")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("TELEGRAM_BOT_TOKEN"));
    }

    @Test
    void context_startsAndExposesToken_whenTokenPresent() {
        new ApplicationContextRunner()
                .withUserConfiguration(TelegramBotProperties.class)
                .withPropertyValues("telegram.bot.token=123456:ABC-DEF")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(TelegramBotProperties.class);
                    assertThat(context.getBean(TelegramBotProperties.class).getToken())
                            .isEqualTo("123456:ABC-DEF");
                });
    }
}