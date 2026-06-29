package io.tykalo.menu.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.tykalo.menu.NewListPendingService;
import io.tykalo.telegram.conversation.ConversationState;
import io.tykalo.telegram.conversation.ConversationStateService;
import io.tykalo.user.User;
import io.tykalo.user.UserRepository;
import java.time.ZoneId;
import java.util.List;
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
class NewListPendingCallbackHandlerTest {

    private static final long CHAT_ID = 100L;
    private static final int MESSAGE_ID = 7;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ConversationStateService conversationState;

    @Mock
    private NewListPendingService newListPendingService;

    @InjectMocks
    private NewListPendingCallbackHandler handler;

    private User user;
    private UUID listId;
    private ConversationState.CreatingListPendingCheck state;

    @BeforeEach
    void setUp() {
        user = User.create(CHAT_ID, "tester", ZoneId.of("Europe/Kyiv"), "en");
        user.setId(UUID.randomUUID());
        listId = UUID.randomUUID();
        state = new ConversationState.CreatingListPendingCheck(listId, List.of(UUID.randomUUID()));
    }

    @Test
    void toggle_isRoutedWithTheParsedItemId() {
        final UUID itemId = UUID.randomUUID();
        stateIsPendingCheck();
        when(newListPendingService.toggle(user, MESSAGE_ID, state, itemId)).thenReturn(Optional.empty());

        assertThat(handler.handle(callbackOnMessage(NewListPendingService.TOGGLE_PREFIX + itemId))).isEmpty();

        verify(newListPendingService).toggle(user, MESSAGE_ID, state, itemId);
    }

    @Test
    void add_drop_skip_areRoutedToTheService() {
        stateIsPendingCheck();
        when(newListPendingService.addSelected(user, MESSAGE_ID, state)).thenReturn(Optional.of("✅ Added 1 item(s)"));
        when(newListPendingService.dropSelected(user, MESSAGE_ID, state)).thenReturn(Optional.of("🗑️ Dropped"));
        when(newListPendingService.skip(user, MESSAGE_ID, state)).thenReturn(Optional.empty());

        assertThat(handler.handle(callbackOnMessage(NewListPendingService.ADD))).get().asString().contains("Added");
        assertThat(handler.handle(callbackOnMessage(NewListPendingService.DROP))).get().asString().contains("Dropped");
        assertThat(handler.handle(callbackOnMessage(NewListPendingService.SKIP))).isEmpty();
    }

    @Test
    void reportsExpired_whenTheFlowHasMovedOn() {
        when(userRepository.findByTgChatId(CHAT_ID)).thenReturn(Optional.of(user));
        when(conversationState.getState(user.getId())).thenReturn(new ConversationState.MainMenu());

        assertThat(handler.handle(callbackOnMessage(NewListPendingService.ADD))).get().asString().contains("expired");
        verifyNoInteractions(newListPendingService);
    }

    @Test
    void reportsExpired_onAMalformedToggleId() {
        stateIsPendingCheck();

        assertThat(handler.handle(callbackOnMessage(NewListPendingService.TOGGLE_PREFIX + "not-a-uuid")))
                .get().asString().contains("expired");
        verify(newListPendingService, never()).toggle(any(), anyInt(), any(), any());
    }

    @Test
    void reportsExpired_whenTheUserIsUnknown() {
        when(userRepository.findByTgChatId(CHAT_ID)).thenReturn(Optional.empty());

        assertThat(handler.handle(callbackOnMessage(NewListPendingService.SKIP))).get().asString().contains("expired");
    }

    @Test
    void nonPendingCallback_isLeftUnclaimed() {
        assertThat(handler.handle(callback("menu:create"))).isEmpty();
        assertThat(handler.handle(callback(null))).isEmpty();
        verifyNoInteractions(userRepository, conversationState, newListPendingService);
    }

    private void stateIsPendingCheck() {
        when(userRepository.findByTgChatId(CHAT_ID)).thenReturn(Optional.of(user));
        when(conversationState.getState(user.getId())).thenReturn(state);
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
