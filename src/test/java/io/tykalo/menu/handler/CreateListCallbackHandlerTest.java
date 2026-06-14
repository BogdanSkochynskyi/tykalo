package io.tykalo.menu.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.tykalo.list.ListType;
import io.tykalo.menu.CreateListService;
import io.tykalo.telegram.conversation.ConversationState;
import io.tykalo.telegram.conversation.ConversationStateService;
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
class CreateListCallbackHandlerTest {

    private static final long CHAT_ID = 100L;
    private static final int MESSAGE_ID = 7;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ConversationStateService conversationState;

    @Mock
    private CreateListService createListService;

    @InjectMocks
    private CreateListCallbackHandler handler;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.create(CHAT_ID, "tester", ZoneId.of("Europe/Kyiv"), "en");
        user.setId(UUID.randomUUID());
    }

    @Test
    void typePick_advancesToTheNamePrompt_whenInTheTypeState() {
        when(userRepository.findByTgChatId(CHAT_ID)).thenReturn(Optional.of(user));
        when(conversationState.getState(user.getId())).thenReturn(new ConversationState.CreatingListType());

        final Optional<String> toast =
                handler.handle(callbackOnMessage(CreateListService.TYPE_PREFIX + ListType.PROJECT.name()));

        assertThat(toast).get().asString().contains("Name your list");
        verify(createListService).chooseType(user, MESSAGE_ID, ListType.PROJECT);
    }

    @Test
    void typePick_isANoOp_whenTheFlowHasMovedOn() {
        when(userRepository.findByTgChatId(CHAT_ID)).thenReturn(Optional.of(user));
        when(conversationState.getState(user.getId())).thenReturn(new ConversationState.MainMenu());

        final Optional<String> toast =
                handler.handle(callbackOnMessage(CreateListService.TYPE_PREFIX + ListType.CHECKLIST.name()));

        assertThat(toast).get().asString().contains("ended");
        verify(createListService, never()).chooseType(user, MESSAGE_ID, ListType.CHECKLIST);
    }

    @Test
    void typePick_rejectsInbox_andUnknownTypes() {
        when(userRepository.findByTgChatId(CHAT_ID)).thenReturn(Optional.of(user));

        assertThat(handler.handle(callbackOnMessage(CreateListService.TYPE_PREFIX + "INBOX")))
                .get().asString().contains("expired");
        assertThat(handler.handle(callbackOnMessage(CreateListService.TYPE_PREFIX + "BOGUS")))
                .get().asString().contains("expired");
        verify(createListService, never()).chooseType(user, MESSAGE_ID, ListType.INBOX);
    }

    @Test
    void cancel_endsTheFlow() {
        when(userRepository.findByTgChatId(CHAT_ID)).thenReturn(Optional.of(user));
        when(createListService.cancel(user, MESSAGE_ID)).thenReturn(Optional.of("Cancelled"));

        final Optional<String> toast = handler.handle(callbackOnMessage(CreateListService.CANCEL));

        assertThat(toast).get().asString().contains("Cancelled");
        verify(createListService).cancel(user, MESSAGE_ID);
    }

    @Test
    void reportsExpired_whenTheUserIsUnknown() {
        when(userRepository.findByTgChatId(CHAT_ID)).thenReturn(Optional.empty());

        assertThat(handler.handle(callbackOnMessage(CreateListService.CANCEL))).get().asString().contains("expired");
    }

    @Test
    void reportsExpired_whenTheMessageIsGone() {
        assertThat(handler.handle(callback(CreateListService.CANCEL))).get().asString().contains("expired");
    }

    @Test
    void nonCreateCallback_isLeftUnclaimed() {
        assertThat(handler.handle(callback("menu:create"))).isEmpty();
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
