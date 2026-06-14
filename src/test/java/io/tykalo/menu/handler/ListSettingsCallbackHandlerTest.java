package io.tykalo.menu.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.tykalo.list.ListType;
import io.tykalo.menu.ListSettingsService;
import io.tykalo.menu.ListViewService;
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
    private ListSettingsService settingsService;

    @Mock
    private ListViewService listViewService;

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
    void rename_startsTheRenameFlow() {
        stubUser();
        when(settingsService.startRename(user, MESSAGE_ID, listId)).thenReturn(Optional.of("✏️ Rename"));

        handler.handle(callbackOnMessage(ListSettingsService.RENAME_PREFIX + listId));

        verify(settingsService).startRename(user, MESSAGE_ID, listId);
    }

    @Test
    void typePicker_isShown() {
        stubUser();
        when(settingsService.showTypePicker(user, MESSAGE_ID, listId)).thenReturn(Optional.of("🔄 Change type"));

        handler.handle(callbackOnMessage(ListSettingsService.TYPE_PREFIX + listId));

        verify(settingsService).showTypePicker(user, MESSAGE_ID, listId);
    }

    @Test
    void settype_appliesTheChosenType() {
        stubUser();
        when(settingsService.changeType(user, MESSAGE_ID, listId, ListType.ROUTINE))
                .thenReturn(Optional.of("🔄 Now a ROUTINE list"));

        handler.handle(callbackOnMessage(
                ListSettingsService.SET_TYPE_PREFIX + listId + ":" + ListType.ROUTINE.name()));

        verify(settingsService).changeType(user, MESSAGE_ID, listId, ListType.ROUTINE);
    }

    @Test
    void settype_rejectsInboxAndBadType() {
        stubUser();

        assertThat(handler.handle(callbackOnMessage(ListSettingsService.SET_TYPE_PREFIX + listId + ":INBOX")))
                .get().asString().contains("expired");
        assertThat(handler.handle(callbackOnMessage(ListSettingsService.SET_TYPE_PREFIX + listId + ":NOPE")))
                .get().asString().contains("expired");
    }

    @Test
    void archive_archivesTheList() {
        stubUser();
        when(settingsService.archive(user, MESSAGE_ID, listId)).thenReturn(Optional.of("📦 Archived"));

        handler.handle(callbackOnMessage(ListSettingsService.ARCHIVE_PREFIX + listId));

        verify(settingsService).archive(user, MESSAGE_ID, listId);
    }

    @Test
    void delete_showsConfirmation_thenDelokDeletes() {
        stubUser();
        when(settingsService.confirmDelete(user, MESSAGE_ID, listId)).thenReturn(Optional.of("🗑️ Confirm delete"));
        when(settingsService.delete(user, MESSAGE_ID, listId)).thenReturn(Optional.of("🗑️ Deleted"));

        handler.handle(callbackOnMessage(ListSettingsService.DELETE_PREFIX + listId));
        handler.handle(callbackOnMessage(ListSettingsService.DELETE_OK_PREFIX + listId));

        verify(settingsService).confirmDelete(user, MESSAGE_ID, listId);
        verify(settingsService).delete(user, MESSAGE_ID, listId);
    }

    @Test
    void menu_reopensTheSettingsScreen() {
        stubUser();
        when(settingsService.open(user, MESSAGE_ID, listId)).thenReturn(Optional.of("Groceries"));

        final Optional<String> toast = handler.handle(callbackOnMessage(ListSettingsService.MENU_PREFIX + listId));

        assertThat(toast).get().asString().contains("Groceries");
        verify(settingsService).open(user, MESSAGE_ID, listId);
    }

    @Test
    void back_returnsToTheListView() {
        stubUser();
        when(listViewService.open(user, MESSAGE_ID, listId)).thenReturn(Optional.of("Groceries"));

        final Optional<String> toast = handler.handle(callbackOnMessage(ListSettingsService.BACK_PREFIX + listId));

        assertThat(toast).get().asString().contains("Groceries");
        verify(listViewService).open(user, MESSAGE_ID, listId);
    }

    @Test
    void reportsExpired_whenUserUnknown() {
        when(userRepository.findByTgChatId(CHAT_ID)).thenReturn(Optional.empty());

        assertThat(handler.handle(callbackOnMessage(ListSettingsService.RENAME_PREFIX + listId)))
                .get().asString().contains("expired");
    }

    @Test
    void reportsExpired_whenMessageGone() {
        assertThat(handler.handle(callback(ListSettingsService.RENAME_PREFIX + listId)))
                .get().asString().contains("expired");
    }

    @Test
    void nonSetCallback_isLeftUnclaimed() {
        assertThat(handler.handle(callback("lv:more:" + listId))).isEmpty();
        assertThat(handler.handle(callback(null))).isEmpty();
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
