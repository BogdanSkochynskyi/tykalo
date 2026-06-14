package io.tykalo.menu.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.tykalo.list.ListType;
import io.tykalo.menu.CreateListService;
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
class CreateListStateHandlerTest {

    @Mock
    private UserService userService;

    @Mock
    private CreateListService createListService;

    @InjectMocks
    private CreateListStateHandler handler;

    private User user;
    private ConversationState.CreatingListName state;

    @BeforeEach
    void setUp() {
        user = User.create(100L, "tester", ZoneId.of("Europe/Kyiv"), "en");
        user.setId(UUID.randomUUID());
        state = new ConversationState.CreatingListName(ListType.PROJECT, 9);
    }

    @Test
    void canHandle_onlyTheCreatingListNameState() {
        assertThat(handler.canHandle(state)).isTrue();
        assertThat(handler.canHandle(new ConversationState.CreatingListType())).isFalse();
        assertThat(handler.canHandle(new ConversationState.Idle())).isFalse();
    }

    @Test
    void handle_delegatesTheTypedName_toTheCreateService() {
        final Update update = TelegramUpdateFixtures.textMessage("Groceries");
        when(userService.find(update)).thenReturn(Optional.of(user));
        when(createListService.submitName(user, state, "Groceries")).thenReturn(Optional.empty());

        assertThat(handler.handle(update, state)).isEmpty();
    }

    @Test
    void handle_returnsEmpty_whenUserUnknown() {
        final Update update = TelegramUpdateFixtures.textMessage("Groceries");
        when(userService.find(update)).thenReturn(Optional.empty());

        assertThat(handler.handle(update, state)).isEmpty();
        verifyNoInteractions(createListService);
    }

    @Test
    void handle_returnsEmpty_whenUpdateHasNoText() {
        assertThat(handler.handle(TelegramUpdateFixtures.withoutMessage(), state)).isEmpty();
        verifyNoInteractions(userService, createListService);
    }
}
