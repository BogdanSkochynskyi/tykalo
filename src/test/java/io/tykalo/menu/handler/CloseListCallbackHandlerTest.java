package io.tykalo.menu.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.tykalo.menu.CloseListService;
import io.tykalo.telegram.CompactUuid;
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
class CloseListCallbackHandlerTest {

    private static final long CHAT_ID = 100L;
    private static final int MESSAGE_ID = 7;

    @Mock
    private UserRepository userRepository;

    @Mock
    private CloseListService closeListService;

    @InjectMocks
    private CloseListCallbackHandler handler;

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
    void nonCloseListCallback_isLeftUnclaimed() {
        assertThat(handler.handle(callback("lv:more:" + listId))).isEmpty();
        verifyNoInteractions(userRepository, closeListService);
    }

    @Test
    void reportsExpired_whenMessageIsGone() {
        assertThat(handler.handle(callback(CloseListService.START_PREFIX + listId)))
                .get().asString().contains("expired");
        verifyNoInteractions(userRepository, closeListService);
    }

    @Test
    void reportsExpired_whenUserUnknown() {
        when(userRepository.findByTgChatId(CHAT_ID)).thenReturn(Optional.empty());
        assertThat(handler.handle(callbackOnMessage(CloseListService.START_PREFIX + listId)))
                .get().asString().contains("expired");
        verifyNoInteractions(closeListService);
    }

    @Test
    void reportsExpired_whenListIdIsNotAUuid() {
        stubUser();
        assertThat(handler.handle(callbackOnMessage(CloseListService.START_PREFIX + "not-a-uuid")))
                .get().asString().contains("expired");
        verifyNoInteractions(closeListService);
    }

    @Test
    void start_opensTheCloseScreen() {
        stubUser();
        when(closeListService.start(user, MESSAGE_ID, listId)).thenReturn(Optional.of("Groceries"));

        assertThat(handler.handle(callbackOnMessage(CloseListService.START_PREFIX + listId)))
                .get().asString().contains("Close list");
        verify(closeListService).start(user, MESSAGE_ID, listId);
    }

    @Test
    void start_reportsGone_whenScreenCannotRender() {
        stubUser();
        when(closeListService.start(user, MESSAGE_ID, listId)).thenReturn(Optional.empty());

        assertThat(handler.handle(callbackOnMessage(CloseListService.START_PREFIX + listId)))
                .get().asString().contains("no longer available");
    }

    @Test
    void confirm_closesAllDoneList() {
        stubUser();
        when(closeListService.confirmClose(user, MESSAGE_ID, listId)).thenReturn(Optional.of("✅ List closed"));

        assertThat(handler.handle(callbackOnMessage(CloseListService.CONFIRM_PREFIX + listId)))
                .get().asString().contains("List closed");
    }

    @Test
    void save_carriesItemsForLater() {
        stubUser();
        when(closeListService.saveForLater(user, MESSAGE_ID, listId)).thenReturn(Optional.of("📌 Saved 2 items for later"));

        assertThat(handler.handle(callbackOnMessage(CloseListService.SAVE_PREFIX + listId)))
                .get().asString().contains("Saved");
    }

    @Test
    void move_opensTheTargetPicker() {
        stubUser();
        when(closeListService.showMovePicker(user, MESSAGE_ID, listId)).thenReturn(Optional.of("Groceries"));

        assertThat(handler.handle(callbackOnMessage(CloseListService.MOVE_PREFIX + listId)))
                .get().asString().contains("Move items");
        verify(closeListService).showMovePicker(user, MESSAGE_ID, listId);
    }

    @Test
    void moveTo_executesTheMove() {
        stubUser();
        final UUID targetId = UUID.randomUUID();
        when(closeListService.moveTo(user, MESSAGE_ID, listId, targetId)).thenReturn(Optional.of("➡️ Moved 1 item"));

        final String data = CloseListService.MOVE_TO_PREFIX
                + CompactUuid.encode(listId) + ":" + CompactUuid.encode(targetId);
        assertThat(handler.handle(callbackOnMessage(data))).get().asString().contains("Moved");
        verify(closeListService).moveTo(user, MESSAGE_ID, listId, targetId);
    }

    @Test
    void moveTo_reportsExpired_whenTokensAreInvalid() {
        stubUser();
        assertThat(handler.handle(callbackOnMessage(CloseListService.MOVE_TO_PREFIX + "abc:def")))
                .get().asString().contains("expired");
        verify(closeListService, never()).moveTo(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void drop_opensTheDropConfirmation() {
        stubUser();
        when(closeListService.showDropConfirm(user, MESSAGE_ID, listId)).thenReturn(Optional.of("Groceries"));

        assertThat(handler.handle(callbackOnMessage(CloseListService.DROP_PREFIX + listId)))
                .get().asString().contains("Drop unfinished");
        verify(closeListService).showDropConfirm(user, MESSAGE_ID, listId);
    }

    @Test
    void dropConfirm_dropsAndCloses() {
        stubUser();
        when(closeListService.drop(user, MESSAGE_ID, listId)).thenReturn(Optional.of("✅ List closed"));

        assertThat(handler.handle(callbackOnMessage(CloseListService.DROP_CONFIRM_PREFIX + listId)))
                .get().asString().contains("List closed");
        verify(closeListService).drop(user, MESSAGE_ID, listId);
    }

    @Test
    void cancel_returnsToTheListView() {
        stubUser();
        when(closeListService.cancel(user, MESSAGE_ID, listId)).thenReturn(Optional.of("Closing cancelled"));

        assertThat(handler.handle(callbackOnMessage(CloseListService.CANCEL_PREFIX + listId)))
                .get().asString().contains("cancelled");
    }

    @Test
    void reopen_reopensTheList() {
        stubUser();
        when(closeListService.reopen(user, MESSAGE_ID, listId)).thenReturn(Optional.of("🔄 List reopened"));

        assertThat(handler.handle(callbackOnMessage(CloseListService.REOPEN_PREFIX + listId)))
                .get().asString().contains("reopened");
        verify(closeListService).reopen(user, MESSAGE_ID, listId);
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
