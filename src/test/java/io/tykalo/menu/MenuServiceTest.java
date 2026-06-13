package io.tykalo.menu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import io.tykalo.telegram.TelegramMessageGateway;
import io.tykalo.telegram.conversation.ConversationState;
import io.tykalo.telegram.conversation.ConversationStateService;
import io.tykalo.user.User;
import java.time.ZoneId;
import java.util.Collection;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

@ExtendWith(MockitoExtension.class)
class MenuServiceTest {

    @Mock
    private TelegramMessageGateway gateway;

    @Mock
    private ConversationStateService conversationState;

    @InjectMocks
    private MenuService service;

    @Test
    void showMainMenu_setsMainMenuState_andSendsTheSixOptionKeyboard() {
        // Arrange
        final User user = User.create(100L, "tester", ZoneId.of("Europe/Kyiv"), "en");
        final UUID userId = UUID.randomUUID();
        user.setId(userId);

        // Act
        service.showMainMenu(user);

        // Assert — conversation state recorded, one keyboard message sent to the user's chat
        verify(conversationState).setState(userId, new ConversationState.MainMenu());
        final ArgumentCaptor<InlineKeyboardMarkup> keyboard = ArgumentCaptor.captor();
        verify(gateway).sendMarkdown(eq(100L), anyString(), keyboard.capture());

        assertThat(keyboard.getValue().getKeyboard()).hasSize(3);
        final var callbackData = keyboard.getValue().getKeyboard().stream()
                .flatMap(Collection::stream)
                .map(InlineKeyboardButton::getCallbackData)
                .toList();
        assertThat(callbackData).containsExactly(
                MenuService.MY_LISTS, MenuService.SHARED, MenuService.CREATE,
                MenuService.STATS, MenuService.SETTINGS, MenuService.HELP);
    }
}
