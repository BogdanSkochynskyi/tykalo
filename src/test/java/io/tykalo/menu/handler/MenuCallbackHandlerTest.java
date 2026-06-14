package io.tykalo.menu.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.tykalo.menu.CreateListService;
import io.tykalo.menu.HelpService;
import io.tykalo.menu.MenuService;
import io.tykalo.menu.MyListsService;
import io.tykalo.user.User;
import io.tykalo.user.UserRepository;
import java.time.ZoneId;
import java.util.List;
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
class MenuCallbackHandlerTest {

    private static final long CHAT_ID = 100L;
    private static final int MESSAGE_ID = 7;

    @Mock
    private UserRepository userRepository;

    @Mock
    private MyListsService myListsService;

    @Mock
    private CreateListService createListService;

    @Mock
    private HelpService helpService;

    @InjectMocks
    private MenuCallbackHandler handler;

    @Test
    void placeholderButtons_areClaimedWithAToast() {
        final List<String> stubButtons = List.of(MenuService.SHARED, MenuService.STATS, MenuService.SETTINGS);

        for (final String action : stubButtons) {
            assertThat(handler.handle(callback(action))).as("toast for %s", action).isPresent();
        }
        verifyNoInteractions(myListsService, createListService, helpService);
    }

    @Test
    void myListsButton_opensTheMyListsScreenInPlace() {
        final User user = User.create(CHAT_ID, "tester", ZoneId.of("Europe/Kyiv"), "en");
        when(userRepository.findByTgChatId(CHAT_ID)).thenReturn(Optional.of(user));

        final Optional<String> toast = handler.handle(callbackOnMessage(MenuService.MY_LISTS));

        assertThat(toast).get().asString().contains("My Lists");
        verify(myListsService).navigate(user, MESSAGE_ID, 0);
    }

    @Test
    void myListsButton_reportsExpired_whenTheUserIsUnknown() {
        when(userRepository.findByTgChatId(CHAT_ID)).thenReturn(Optional.empty());

        assertThat(handler.handle(callbackOnMessage(MenuService.MY_LISTS))).get().asString().contains("expired");
    }

    @Test
    void helpButton_opensTheHelpScreenInPlace() {
        final User user = User.create(CHAT_ID, "tester", ZoneId.of("Europe/Kyiv"), "en");
        when(userRepository.findByTgChatId(CHAT_ID)).thenReturn(Optional.of(user));

        final Optional<String> toast = handler.handle(callbackOnMessage(MenuService.HELP));

        assertThat(toast).get().asString().contains("Help");
        verify(helpService).navigate(user, MESSAGE_ID);
    }

    @Test
    void createButton_startsTheCreateFlowInPlace() {
        final User user = User.create(CHAT_ID, "tester", ZoneId.of("Europe/Kyiv"), "en");
        when(userRepository.findByTgChatId(CHAT_ID)).thenReturn(Optional.of(user));

        final Optional<String> toast = handler.handle(callbackOnMessage(MenuService.CREATE));

        assertThat(toast).get().asString().contains("New list");
        verify(createListService).start(user, MESSAGE_ID);
    }

    @Test
    void createButton_reportsExpired_whenTheUserIsUnknown() {
        when(userRepository.findByTgChatId(CHAT_ID)).thenReturn(Optional.empty());

        assertThat(handler.handle(callbackOnMessage(MenuService.CREATE))).get().asString().contains("expired");
    }

    @Test
    void nonMenuCallback_isLeftUnclaimed() {
        assertThat(handler.handle(callback("task:done:abc"))).isEmpty();
    }

    @Test
    void unknownMenuAction_isLeftUnclaimed() {
        assertThat(handler.handle(callback("menu:bogus"))).isEmpty();
    }

    @Test
    void nullCallbackData_isLeftUnclaimed() {
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
