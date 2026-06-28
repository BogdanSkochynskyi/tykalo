package io.tykalo.menu.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.tykalo.menu.ListSettingsService;
import io.tykalo.user.User;
import io.tykalo.user.UserRepository;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;

@ExtendWith(MockitoExtension.class)
class ListSettingsCallbackHandlerTest {

    private static final long CHAT_ID = 100L;
    private static final int MESSAGE_ID = 7;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ListSettingsService listSettingsService;

    @InjectMocks
    private ListSettingsCallbackHandler handler;

    private User user;
    private UUID listId;

    @BeforeEach
    void setUp() {
        user = User.create(CHAT_ID, "tester", ZoneId.of("Europe/Kyiv"), "en");
        user.setId(UUID.randomUUID());
        listId = UUID.randomUUID();
    }

    private void stubUser() {
        when(userRepository.findByTgChatId(CHAT_ID)).thenReturn(Optional.of(user));
    }

    @Test
    void nonListSettingsCallback_isLeftUnclaimed() {
        assertThat(handler.handle(callback("lv:more:" + listId))).isEmpty();
        verifyNoInteractions(userRepository, listSettingsService);
    }

    @Test
    void reportsExpired_whenMessageIsGone() {
        assertThat(handler.handle(callback(ListSettingsService.TOGGLE_AUTO_CLOSE_PREFIX + listId)))
                .get().asString().contains("expired");
        verifyNoInteractions(userRepository, listSettingsService);
    }

    @Test
    void toggleAutoClose_flipsTheSetting() {
        stubUser();
        when(listSettingsService.toggleAutoClose(user, MESSAGE_ID, listId)).thenReturn(Optional.of("🤖 Auto-close on"));

        assertThat(handler.handle(callbackOnMessage(ListSettingsService.TOGGLE_AUTO_CLOSE_PREFIX + listId)))
                .get().asString().contains("Auto-close on");
        verify(listSettingsService).toggleAutoClose(user, MESSAGE_ID, listId);
    }

    @Test
    void toggleAutoClose_reportsGone_whenNotEditable() {
        stubUser();
        when(listSettingsService.toggleAutoClose(user, MESSAGE_ID, listId)).thenReturn(Optional.empty());

        assertThat(handler.handle(callbackOnMessage(ListSettingsService.TOGGLE_AUTO_CLOSE_PREFIX + listId)))
                .get().asString().contains("can't edit");
    }

    @Test
    void back_returnsToTheListView() {
        stubUser();
        when(listSettingsService.back(user, MESSAGE_ID, listId)).thenReturn(Optional.of("Groceries"));

        assertThat(handler.handle(callbackOnMessage(ListSettingsService.BACK_PREFIX + listId)))
                .get().asString().contains("Back");
        verify(listSettingsService).back(user, MESSAGE_ID, listId);
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
