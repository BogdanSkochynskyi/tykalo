package io.tykalo.menu.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.tykalo.menu.MenuService;
import io.tykalo.menu.SettingsService;
import io.tykalo.user.ListChangeNotificationPreference;
import io.tykalo.user.User;
import io.tykalo.user.UserRepository;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;

@ExtendWith(MockitoExtension.class)
class SettingsCallbackHandlerTest {

    private static final long CHAT_ID = 100L;
    private static final int MESSAGE_ID = 7;

    @Mock
    private UserRepository userRepository;

    @Mock
    private SettingsService settingsService;

    @Mock
    private MenuService menuService;

    @InjectMocks
    private SettingsCallbackHandler handler;

    @Test
    void notifSelect_persistsPreference_inPlace() {
        final User user = knownUser();

        final Optional<String> toast = handler.handle(
                callbackOnMessage(SettingsService.NOTIF_PREFIX + "INSTANT"));

        assertThat(toast).get().asString().contains("Saved");
        verify(settingsService).select(user, MESSAGE_ID, ListChangeNotificationPreference.INSTANT);
    }

    @Test
    void menuButton_returnsToMainMenu() {
        final User user = knownUser();

        final Optional<String> toast = handler.handle(callbackOnMessage(SettingsService.MENU));

        assertThat(toast).get().asString().contains("Main menu");
        verify(menuService).editToMainMenu(user, MESSAGE_ID);
    }

    @Test
    void unknownPreferenceToken_reportsExpired() {
        knownUser();

        assertThat(handler.handle(callbackOnMessage(SettingsService.NOTIF_PREFIX + "BOGUS")))
                .get().asString().contains("expired");
    }

    @Test
    void unknownUser_reportsExpired() {
        when(userRepository.findByTgChatId(CHAT_ID)).thenReturn(Optional.empty());

        assertThat(handler.handle(callbackOnMessage(SettingsService.MENU))).get().asString().contains("expired");
        verifyNoInteractions(settingsService, menuService);
    }

    @Test
    void nonSettingsCallback_isLeftUnclaimed() {
        assertThat(handler.handle(callback("menu:my_lists"))).isEmpty();
        assertThat(handler.handle(callback(null))).isEmpty();
        verifyNoInteractions(userRepository, settingsService, menuService);
    }

    private User knownUser() {
        final User user = User.create(CHAT_ID, "tester", ZoneId.of("Europe/Kyiv"), "en");
        when(userRepository.findByTgChatId(CHAT_ID)).thenReturn(Optional.of(user));
        return user;
    }

    private CallbackQuery callback(final String data) {
        final CallbackQuery query = new CallbackQuery();
        query.setId("cb-id");
        query.setData(data);
        return query;
    }

    private CallbackQuery callbackOnMessage(final String data) {
        final Message message = new Message();
        message.setMessageId(MESSAGE_ID);
        message.setChat(new Chat(CHAT_ID, "private"));

        final CallbackQuery query = callback(data);
        query.setMessage(message);
        return query;
    }
}
