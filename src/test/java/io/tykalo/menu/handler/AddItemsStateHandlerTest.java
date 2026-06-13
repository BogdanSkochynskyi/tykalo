package io.tykalo.menu.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.tykalo.menu.AddItemsService;
import io.tykalo.telegram.TelegramUpdateFixtures;
import io.tykalo.telegram.conversation.ConversationState;
import io.tykalo.user.User;
import io.tykalo.user.UserService;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.objects.Update;

@ExtendWith(MockitoExtension.class)
class AddItemsStateHandlerTest {

    @Mock
    private UserService userService;

    @Mock
    private AddItemsService addItemsService;

    @InjectMocks
    private AddItemsStateHandler handler;

    private User user;
    private ConversationState.AddingItems state;

    @BeforeEach
    void setUp() {
        user = User.create(100L, "tester", ZoneId.of("Europe/Kyiv"), "en");
        user.setId(UUID.randomUUID());
        state = new ConversationState.AddingItems(UUID.randomUUID(), 9);
    }

    @Test
    void canHandle_onlyTheAddingItemsState() {
        assertThat(handler.canHandle(state)).isTrue();
        assertThat(handler.canHandle(new ConversationState.ListView(UUID.randomUUID()))).isFalse();
        assertThat(handler.canHandle(new ConversationState.Idle())).isFalse();
    }

    @Test
    void handle_delegatesTheMessageText_toTheAddService() {
        final Update update = TelegramUpdateFixtures.textMessage("Milk");
        when(userService.find(update)).thenReturn(Optional.of(user));
        when(addItemsService.addItems(user, state, "Milk")).thenReturn(Optional.empty());

        assertThat(handler.handle(update, state)).isEmpty();
    }

    @Test
    void handle_passesThroughTheServiceReply() {
        final Update update = TelegramUpdateFixtures.textMessage("Milk");
        when(userService.find(update)).thenReturn(Optional.of(user));
        when(addItemsService.addItems(user, state, "Milk")).thenReturn(Optional.of("ended"));

        assertThat(handler.handle(update, state)).contains("ended");
    }

    @Test
    void handle_returnsEmpty_whenUserUnknown() {
        final Update update = TelegramUpdateFixtures.textMessage("Milk");
        when(userService.find(update)).thenReturn(Optional.empty());

        assertThat(handler.handle(update, state)).isEmpty();
        verifyNoInteractions(addItemsService);
    }

    @Test
    void handle_returnsEmpty_whenUpdateHasNoText() {
        assertThat(handler.handle(TelegramUpdateFixtures.withoutMessage(), state)).isEmpty();
        verifyNoInteractions(userService, addItemsService);
    }
}
