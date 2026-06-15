package io.tykalo.menu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.tykalo.telegram.TelegramMessageGateway;
import io.tykalo.telegram.conversation.ConversationState;
import io.tykalo.telegram.conversation.ConversationStateService;
import io.tykalo.user.ListChangeNotificationPreference;
import io.tykalo.user.User;
import io.tykalo.user.UserService;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

@ExtendWith(MockitoExtension.class)
class SettingsServiceTest {

    private static final long CHAT_ID = 100L;
    private static final int MESSAGE_ID = 7;

    @Mock
    private UserService userService;

    @Mock
    private ConversationStateService conversationState;

    @Mock
    private TelegramMessageGateway gateway;

    @InjectMocks
    private SettingsService settingsService;

    private User user(final ListChangeNotificationPreference pref) {
        final User user = User.create(CHAT_ID, "tester", ZoneId.of("UTC"), "en");
        user.setId(UUID.randomUUID());
        user.setListChangeNotifications(pref);
        return user;
    }

    @Test
    void open_sendsScreen_setsState_andMarksCurrentPreference() {
        final User user = user(ListChangeNotificationPreference.BATCHED);

        settingsService.open(user);

        verify(conversationState).setState(user.getId(), new ConversationState.Settings());
        final ArgumentCaptor<InlineKeyboardMarkup> keyboard = ArgumentCaptor.forClass(InlineKeyboardMarkup.class);
        verify(gateway).sendMarkdown(org.mockito.ArgumentMatchers.eq(CHAT_ID),
                org.mockito.ArgumentMatchers.anyString(), keyboard.capture());
        assertThat(isMarked(keyboard.getValue(), ListChangeNotificationPreference.BATCHED)).isTrue();
        assertThat(isMarked(keyboard.getValue(), ListChangeNotificationPreference.OFF)).isFalse();
    }

    @Test
    void select_persistsPreference_andReRendersWithNewMark() {
        final User user = user(ListChangeNotificationPreference.BATCHED);
        final User updated = user(ListChangeNotificationPreference.INSTANT);
        when(userService.setListChangeNotifications(user, ListChangeNotificationPreference.INSTANT))
                .thenReturn(updated);

        settingsService.select(user, MESSAGE_ID, ListChangeNotificationPreference.INSTANT);

        verify(userService).setListChangeNotifications(user, ListChangeNotificationPreference.INSTANT);
        final ArgumentCaptor<InlineKeyboardMarkup> keyboard = ArgumentCaptor.forClass(InlineKeyboardMarkup.class);
        verify(gateway).editMarkdown(org.mockito.ArgumentMatchers.eq(CHAT_ID),
                org.mockito.ArgumentMatchers.eq(MESSAGE_ID), org.mockito.ArgumentMatchers.anyString(),
                keyboard.capture());
        assertThat(isMarked(keyboard.getValue(), ListChangeNotificationPreference.INSTANT)).isTrue();
        assertThat(isMarked(keyboard.getValue(), ListChangeNotificationPreference.BATCHED)).isFalse();
    }

    private boolean isMarked(final InlineKeyboardMarkup keyboard, final ListChangeNotificationPreference pref) {
        final String callbackData = SettingsService.NOTIF_PREFIX + pref.name();
        for (final InlineKeyboardRow row : keyboard.getKeyboard()) {
            for (final InlineKeyboardButton button : row) {
                if (callbackData.equals(button.getCallbackData())) {
                    return button.getText().startsWith("🔘");
                }
            }
        }
        return false;
    }
}
