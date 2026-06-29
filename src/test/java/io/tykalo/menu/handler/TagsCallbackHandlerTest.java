package io.tykalo.menu.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.tykalo.menu.TagsService;
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
class TagsCallbackHandlerTest {

    private static final long CHAT_ID = 100L;
    private static final int MESSAGE_ID = 7;

    @Mock
    private UserRepository userRepository;

    @Mock
    private TagsService tagsService;

    @InjectMocks
    private TagsCallbackHandler handler;

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
    void nonTagCallback_isLeftUnclaimed() {
        assertThat(handler.handle(callback("ls:back:" + listId))).isEmpty();
        verifyNoInteractions(userRepository, tagsService);
    }

    @Test
    void reportsExpired_whenMessageIsGone() {
        assertThat(handler.handle(callback(TagsService.OPEN_PREFIX + listId)))
                .get().asString().contains("expired");
        verifyNoInteractions(userRepository, tagsService);
    }

    @Test
    void open_rendersTheTagsScreen() {
        stubUser();
        when(tagsService.open(user, MESSAGE_ID, listId)).thenReturn(Optional.of("Groceries"));

        assertThat(handler.handle(onMessage(TagsService.OPEN_PREFIX + listId)))
                .get().asString().contains("Tags");
        verify(tagsService).open(user, MESSAGE_ID, listId);
    }

    @Test
    void open_reportsGone_whenNotEditable() {
        stubUser();
        when(tagsService.open(user, MESSAGE_ID, listId)).thenReturn(Optional.empty());

        assertThat(handler.handle(onMessage(TagsService.OPEN_PREFIX + listId)))
                .get().asString().contains("can't edit");
    }

    @Test
    void promptAdd_opensThePrompt() {
        stubUser();
        when(tagsService.promptAdd(user, MESSAGE_ID, listId)).thenReturn(Optional.of("Groceries"));

        assertThat(handler.handle(onMessage(TagsService.ADD_PREFIX + listId)))
                .get().asString().contains("Add a tag");
        verify(tagsService).promptAdd(user, MESSAGE_ID, listId);
    }

    @Test
    void quickAdd_routesTheSuggestionIndex() {
        stubUser();
        when(tagsService.quickAdd(user, MESSAGE_ID, listId, 2)).thenReturn(Optional.of("🏷️ Added #work"));

        assertThat(handler.handle(onMessage(TagsService.QUICK_PREFIX + listId + ":2")))
                .get().asString().contains("work");
        verify(tagsService).quickAdd(user, MESSAGE_ID, listId, 2);
    }

    @Test
    void remove_routesToConfirmation() {
        stubUser();
        when(tagsService.confirmRemove(user, MESSAGE_ID, listId, 1)).thenReturn(Optional.of("Groceries"));

        assertThat(handler.handle(onMessage(TagsService.REMOVE_PREFIX + listId + ":1")))
                .get().asString().contains("removal");
        verify(tagsService).confirmRemove(user, MESSAGE_ID, listId, 1);
    }

    @Test
    void removeConfirm_routesToRemoval() {
        stubUser();
        when(tagsService.remove(user, MESSAGE_ID, listId, 1)).thenReturn(Optional.of("🗑️ Removed #home"));

        assertThat(handler.handle(onMessage(TagsService.REMOVE_CONFIRM_PREFIX + listId + ":1")))
                .get().asString().contains("home");
        verify(tagsService).remove(user, MESSAGE_ID, listId, 1);
    }

    @Test
    void back_returnsToSettings() {
        stubUser();
        when(tagsService.back(user, MESSAGE_ID, listId)).thenReturn(Optional.of("Groceries"));

        assertThat(handler.handle(onMessage(TagsService.BACK_PREFIX + listId)))
                .get().asString().contains("Settings");
        verify(tagsService).back(user, MESSAGE_ID, listId);
    }

    @Test
    void indexedCallback_withMalformedIndex_reportsExpired() {
        stubUser();

        assertThat(handler.handle(onMessage(TagsService.QUICK_PREFIX + listId + ":x")))
                .get().asString().contains("expired");
    }

    private CallbackQuery callback(final String data) {
        final CallbackQuery query = new CallbackQuery();
        query.setId("cb-id");
        query.setData(data);
        return query;
    }

    private CallbackQuery onMessage(final String data) {
        final Message message = new Message();
        message.setMessageId(MESSAGE_ID);
        message.setChat(new Chat(CHAT_ID, "private"));
        final CallbackQuery query = callback(data);
        query.setMessage(message);
        return query;
    }
}
