package io.tykalo.nudger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import io.tykalo.telegram.TelegramMessageGateway;
import io.tykalo.user.User;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

@ExtendWith(MockitoExtension.class)
class NudgerPromptServiceTest {

    @Mock
    private TelegramMessageGateway gateway;

    @Captor
    private ArgumentCaptor<InlineKeyboardMarkup> keyboardCaptor;

    @InjectMocks
    private NudgerPromptService promptService;

    @Test
    void sendConsentPrompt_sendsToInvitee_withAcceptAndDeclineButtons() {
        // Arrange
        final User owner = user(7L, "alice");
        final User invitee = user(42L, "bob");
        final Nudger nudger = Nudger.invite(owner, invitee);
        nudger.setId(UUID.randomUUID());

        // Act
        promptService.sendConsentPrompt(nudger, invitee, owner);

        // Assert — prompt goes to the invitee's chat, names the owner, carries both callbacks
        verify(gateway).sendMarkdown(eq(42L), contains("@alice"), keyboardCaptor.capture());
        final var buttons = keyboardCaptor.getValue().getKeyboard().getFirst();
        assertThat(buttons).extracting(InlineKeyboardButton::getCallbackData)
                .containsExactly("nudger:accept:" + nudger.getId(), "nudger:decline:" + nudger.getId());
    }

    private User user(final long tgChatId, final String username) {
        final User user = User.create(tgChatId, username, ZoneId.of("Europe/Kyiv"), "uk");
        user.setId(UUID.randomUUID());
        return user;
    }
}
