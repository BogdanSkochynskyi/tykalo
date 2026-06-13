package io.tykalo.menu.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.tykalo.menu.ListViewService;
import io.tykalo.menu.MenuService;
import io.tykalo.menu.MyListsService;
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
class MyListsCallbackHandlerTest {

    private static final long CHAT_ID = 100L;
    private static final int MESSAGE_ID = 7;

    @Mock
    private UserRepository userRepository;

    @Mock
    private MyListsService myListsService;

    @Mock
    private MenuService menuService;

    @Mock
    private ListViewService listViewService;

    @InjectMocks
    private MyListsCallbackHandler handler;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.create(CHAT_ID, "tester", ZoneId.of("Europe/Kyiv"), "en");
        user.setId(UUID.randomUUID());
    }

    @Test
    void page_navigatesToTheRequestedPage() {
        when(userRepository.findByTgChatId(CHAT_ID)).thenReturn(Optional.of(user));

        final Optional<String> toast = handler.handle(callbackOnMessage(MyListsService.PAGE_PREFIX + 2));

        assertThat(toast).get().asString().contains("Page 3");
        verify(myListsService).navigate(user, MESSAGE_ID, 2);
    }

    @Test
    void back_returnsToTheMainMenu() {
        when(userRepository.findByTgChatId(CHAT_ID)).thenReturn(Optional.of(user));

        final Optional<String> toast = handler.handle(callbackOnMessage(MyListsService.BACK));

        assertThat(toast).get().asString().contains("Main menu");
        verify(menuService).editToMainMenu(user, MESSAGE_ID);
    }

    @Test
    void open_opensTheListView() {
        final UUID listId = UUID.randomUUID();
        when(userRepository.findByTgChatId(CHAT_ID)).thenReturn(Optional.of(user));
        when(listViewService.open(user, MESSAGE_ID, listId)).thenReturn(Optional.of("Groceries"));

        final Optional<String> toast = handler.handle(callbackOnMessage(MyListsService.OPEN_PREFIX + listId));

        assertThat(toast).get().asString().contains("Groceries");
        verify(listViewService).open(user, MESSAGE_ID, listId);
        verifyNoInteractions(myListsService, menuService);
    }

    @Test
    void open_reportsListGone_whenTheListNoLongerExists() {
        final UUID listId = UUID.randomUUID();
        when(userRepository.findByTgChatId(CHAT_ID)).thenReturn(Optional.of(user));
        when(listViewService.open(user, MESSAGE_ID, listId)).thenReturn(Optional.empty());

        assertThat(handler.handle(callbackOnMessage(MyListsService.OPEN_PREFIX + listId)))
                .get().asString().contains("no longer available");
    }

    @Test
    void newList_isAStubToast_pointingAtTheCreateCommand() {
        final Optional<String> toast = handler.handle(callbackOnMessage(MyListsService.NEW));

        assertThat(toast).get().asString().contains("/list create");
        verifyNoInteractions(myListsService, menuService);
    }

    @Test
    void back_reportsExpired_whenUserUnknown() {
        when(userRepository.findByTgChatId(CHAT_ID)).thenReturn(Optional.empty());

        assertThat(handler.handle(callbackOnMessage(MyListsService.BACK))).get().asString().contains("expired");
        verifyNoInteractions(menuService);
    }

    @Test
    void page_reportsExpired_whenTheMessageIsGone() {
        // No message on the callback → nothing to edit in place.
        assertThat(handler.handle(callback(MyListsService.PAGE_PREFIX + 1))).get().asString().contains("expired");
    }

    @Test
    void nonListsCallback_isLeftUnclaimed() {
        assertThat(handler.handle(callback("menu:my_lists"))).isEmpty();
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
