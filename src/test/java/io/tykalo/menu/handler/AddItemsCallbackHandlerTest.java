package io.tykalo.menu.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.tykalo.menu.AddItemsService;
import io.tykalo.telegram.TelegramMessageGateway;
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
class AddItemsCallbackHandlerTest {

    private static final long CHAT_ID = 100L;
    private static final int PROMPT_MESSAGE_ID = 11;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ConversationStateService conversationState;

    @Mock
    private AddItemsService addItemsService;

    @Mock
    private TelegramMessageGateway gateway;

    @InjectMocks
    private AddItemsCallbackHandler handler;

    private User user;
    private ConversationState.AddingItems adding;

    @BeforeEach
    void setUp() {
        user = User.create(CHAT_ID, "tester", ZoneId.of("Europe/Kyiv"), "en");
        user.setId(UUID.randomUUID());
        adding = new ConversationState.AddingItems(UUID.randomUUID(), 9);
    }

    @Test
    void nonAddCallback_isLeftUnclaimed() {
        assertThat(handler.handle(callback("lv:add:" + UUID.randomUUID()))).isEmpty();
        verifyNoInteractions(userRepository, conversationState, addItemsService);
    }

    @Test
    void unrecognisedAddCallback_isLeftUnclaimed() {
        assertThat(handler.handle(callback("add:whatever"))).isEmpty();
        verifyNoInteractions(userRepository, conversationState, addItemsService);
    }

    @Test
    void done_finishesTheFlow_withDoneTrue() {
        when(userRepository.findByTgChatId(CHAT_ID)).thenReturn(Optional.of(user));
        when(conversationState.getState(user.getId())).thenReturn(adding);
        when(addItemsService.finish(user, adding, PROMPT_MESSAGE_ID, true))
                .thenReturn(Optional.of("✅ Items added"));

        assertThat(handler.handle(callbackOnMessage(AddItemsService.DONE))).get().asString().contains("added");
        verify(addItemsService).finish(user, adding, PROMPT_MESSAGE_ID, true);
    }

    @Test
    void cancel_finishesTheFlow_withDoneFalse() {
        when(userRepository.findByTgChatId(CHAT_ID)).thenReturn(Optional.of(user));
        when(conversationState.getState(user.getId())).thenReturn(adding);
        when(addItemsService.finish(user, adding, PROMPT_MESSAGE_ID, false))
                .thenReturn(Optional.of("Stopped adding"));

        assertThat(handler.handle(callbackOnMessage(AddItemsService.CANCEL))).get().asString().contains("Stopped");
        verify(addItemsService).finish(user, adding, PROMPT_MESSAGE_ID, false);
    }

    @Test
    void stalePrompt_isDeleted_whenFlowAlreadyEnded() {
        when(userRepository.findByTgChatId(CHAT_ID)).thenReturn(Optional.of(user));
        when(conversationState.getState(user.getId())).thenReturn(new ConversationState.Idle());

        assertThat(handler.handle(callbackOnMessage(AddItemsService.DONE))).get().asString().contains("ended");
        verify(gateway).deleteMessage(CHAT_ID, PROMPT_MESSAGE_ID);
        verifyNoInteractions(addItemsService);
    }

    @Test
    void reportsExpired_whenCallbackHasNoMessage() {
        assertThat(handler.handle(callback(AddItemsService.DONE))).get().asString().contains("expired");
        verifyNoInteractions(userRepository, conversationState, addItemsService);
    }

    private CallbackQuery callback(final String data) {
        final CallbackQuery query = new CallbackQuery();
        query.setId("cb-id");
        query.setData(data);
        return query;
    }

    private CallbackQuery callbackOnMessage(final String data) {
        final Message message = new Message();
        message.setMessageId(PROMPT_MESSAGE_ID);
        message.setChat(new Chat(CHAT_ID, "private"));

        final CallbackQuery query = callback(data);
        query.setMessage(message);
        return query;
    }
}
