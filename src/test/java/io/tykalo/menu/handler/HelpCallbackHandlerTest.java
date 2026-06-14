package io.tykalo.menu.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.tykalo.menu.HelpService;
import io.tykalo.menu.HelpTopic;
import io.tykalo.menu.MenuService;
import io.tykalo.user.User;
import io.tykalo.user.UserRepository;
import java.time.ZoneId;
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
class HelpCallbackHandlerTest {

    private static final long CHAT_ID = 100L;
    private static final int MESSAGE_ID = 7;

    @Mock
    private UserRepository userRepository;

    @Mock
    private HelpService helpService;

    @Mock
    private MenuService menuService;

    @InjectMocks
    private HelpCallbackHandler handler;

    @Test
    void categoryButton_drillsIntoThatTopicInPlace() {
        final User user = knownUser();

        final Optional<String> toast = handler.handle(callbackOnMessage(HelpService.CATEGORY_PREFIX + "NUDGERS"));

        assertThat(toast).get().asString().contains("Nudgers");
        verify(helpService).showCategory(user, MESSAGE_ID, HelpTopic.NUDGERS);
    }

    @Test
    void backButton_returnsToTheTopLevelHelp() {
        final User user = knownUser();

        final Optional<String> toast = handler.handle(callbackOnMessage(HelpService.BACK));

        assertThat(toast).get().asString().contains("Help");
        verify(helpService).navigate(user, MESSAGE_ID);
        verifyNoInteractions(menuService);
    }

    @Test
    void menuButton_returnsToTheMainMenu() {
        final User user = knownUser();

        final Optional<String> toast = handler.handle(callbackOnMessage(HelpService.MENU));

        assertThat(toast).get().asString().contains("Main menu");
        verify(menuService).editToMainMenu(user, MESSAGE_ID);
    }

    @Test
    void unknownCategory_reportsExpired() {
        knownUser();

        assertThat(handler.handle(callbackOnMessage(HelpService.CATEGORY_PREFIX + "BOGUS")))
                .get().asString().contains("expired");
    }

    @Test
    void unknownUser_reportsExpired() {
        when(userRepository.findByTgChatId(CHAT_ID)).thenReturn(Optional.empty());

        assertThat(handler.handle(callbackOnMessage(HelpService.BACK))).get().asString().contains("expired");
        verifyNoInteractions(helpService, menuService);
    }

    @Test
    void nonHelpCallback_isLeftUnclaimed() {
        assertThat(handler.handle(callback("menu:my_lists"))).isEmpty();
        assertThat(handler.handle(callback(null))).isEmpty();
        verifyNoInteractions(userRepository, helpService, menuService);
    }

    private User knownUser() {
        final User user = User.create(CHAT_ID, "tester", ZoneId.of("Europe/Kyiv"), "en");
        when(userRepository.findByTgChatId(CHAT_ID)).thenReturn(Optional.of(user));
        return user;
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
