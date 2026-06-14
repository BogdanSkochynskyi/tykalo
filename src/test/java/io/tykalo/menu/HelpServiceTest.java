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
class HelpServiceTest {

    private static final long CHAT_ID = 100L;
    private static final int MESSAGE_ID = 7;

    @Mock
    private ConversationStateService conversationState;

    @Mock
    private TelegramMessageGateway gateway;

    @InjectMocks
    private HelpService service;

    @Test
    void open_setsHelpState_andSendsACategoryButtonPerTopicPlusBackToMenu() {
        final User user = user();

        service.open(user);

        verify(conversationState).setState(user.getId(), new ConversationState.Help());
        final ArgumentCaptor<InlineKeyboardMarkup> keyboard = ArgumentCaptor.captor();
        verify(gateway).sendMarkdown(eq(CHAT_ID), anyString(), keyboard.capture());

        final var callbackData = keyboard.getValue().getKeyboard().stream()
                .flatMap(Collection::stream)
                .map(InlineKeyboardButton::getCallbackData)
                .toList();
        assertThat(callbackData).containsExactly(
                HelpService.CATEGORY_PREFIX + "LISTS",
                HelpService.CATEGORY_PREFIX + "NUDGERS",
                HelpService.CATEGORY_PREFIX + "SCHEDULING",
                HelpService.CATEGORY_PREFIX + "SETTINGS",
                HelpService.MENU);
    }

    @Test
    void navigate_editsTopLevelInPlace_andSetsHelpState() {
        final User user = user();

        service.navigate(user, MESSAGE_ID);

        verify(conversationState).setState(user.getId(), new ConversationState.Help());
        verify(gateway).editMarkdown(eq(CHAT_ID), eq(MESSAGE_ID), anyString(), anyKeyboard());
    }

    @Test
    void showCategory_editsInPlace_setsCategoryState_andRendersThatTopicsCommands() {
        final User user = user();

        service.showCategory(user, MESSAGE_ID, HelpTopic.NUDGERS);

        verify(conversationState).setState(user.getId(), new ConversationState.HelpCategory(HelpTopic.NUDGERS));
        final ArgumentCaptor<String> text = ArgumentCaptor.captor();
        final ArgumentCaptor<InlineKeyboardMarkup> keyboard = ArgumentCaptor.captor();
        verify(gateway).editMarkdown(eq(CHAT_ID), eq(MESSAGE_ID), text.capture(), keyboard.capture());

        assertThat(text.getValue()).contains("Nudgers").contains("/nudgers add");
        final var callbackData = keyboard.getValue().getKeyboard().stream()
                .flatMap(Collection::stream)
                .map(InlineKeyboardButton::getCallbackData)
                .toList();
        assertThat(callbackData).containsExactly(HelpService.BACK);
    }

    private User user() {
        final User user = User.create(CHAT_ID, "tester", ZoneId.of("Europe/Kyiv"), "en");
        user.setId(UUID.randomUUID());
        return user;
    }

    private static InlineKeyboardMarkup anyKeyboard() {
        return org.mockito.ArgumentMatchers.any();
    }
}
