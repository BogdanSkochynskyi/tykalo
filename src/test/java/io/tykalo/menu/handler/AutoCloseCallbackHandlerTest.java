package io.tykalo.menu.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.tykalo.menu.AutoCloseService;
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
class AutoCloseCallbackHandlerTest {

    private static final long CHAT_ID = 100L;
    private static final int MESSAGE_ID = 7;

    @Mock
    private UserRepository userRepository;

    @Mock
    private AutoCloseService autoCloseService;

    @InjectMocks
    private AutoCloseCallbackHandler handler;

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
    void nonAutoCloseCallback_isLeftUnclaimed() {
        assertThat(handler.handle(callback("lv:more:" + listId))).isEmpty();
        verifyNoInteractions(userRepository, autoCloseService);
    }

    @Test
    void reportsExpired_whenMessageIsGone() {
        assertThat(handler.handle(callback(AutoCloseService.CLOSE_PREFIX + listId)))
                .get().asString().contains("expired");
        verifyNoInteractions(userRepository, autoCloseService);
    }

    @Test
    void reportsExpired_whenUserUnknown() {
        when(userRepository.findByTgChatId(CHAT_ID)).thenReturn(Optional.empty());
        assertThat(handler.handle(callbackOnMessage(AutoCloseService.CLOSE_PREFIX + listId)))
                .get().asString().contains("expired");
        verifyNoInteractions(autoCloseService);
    }

    @Test
    void close_closesTheList() {
        stubUser();
        when(autoCloseService.confirmClose(user, MESSAGE_ID, listId)).thenReturn(Optional.of("✅ List closed"));

        assertThat(handler.handle(callbackOnMessage(AutoCloseService.CLOSE_PREFIX + listId)))
                .get().asString().contains("List closed");
        verify(autoCloseService).confirmClose(user, MESSAGE_ID, listId);
    }

    @Test
    void close_reportsGone_whenListMissing() {
        stubUser();
        when(autoCloseService.confirmClose(user, MESSAGE_ID, listId)).thenReturn(Optional.empty());

        assertThat(handler.handle(callbackOnMessage(AutoCloseService.CLOSE_PREFIX + listId)))
                .get().asString().contains("no longer available");
    }

    @Test
    void keep_keepsTheListOpen() {
        stubUser();
        when(autoCloseService.keepOpen(user, MESSAGE_ID, listId)).thenReturn(Optional.of("👍 Keeping it open"));

        assertThat(handler.handle(callbackOnMessage(AutoCloseService.KEEP_PREFIX + listId)))
                .get().asString().contains("Keeping it open");
        verify(autoCloseService).keepOpen(user, MESSAGE_ID, listId);
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
