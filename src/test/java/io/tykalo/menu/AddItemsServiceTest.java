package io.tykalo.menu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.tykalo.list.ListService;
import io.tykalo.list.ListType;
import io.tykalo.list.TaskList;
import io.tykalo.list.TaskService;
import io.tykalo.telegram.TelegramMessageGateway;
import io.tykalo.telegram.conversation.ConversationState;
import io.tykalo.telegram.conversation.ConversationStateService;
import io.tykalo.user.User;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AddItemsServiceTest {

    private static final long CHAT_ID = 555L;
    private static final int LIST_VIEW_MESSAGE_ID = 9;
    private static final int PROMPT_MESSAGE_ID = 10;

    @Mock
    private ListService listService;

    @Mock
    private TaskService taskService;

    @Mock
    private ConversationStateService conversationState;

    @Mock
    private ListViewService listViewService;

    @Mock
    private TelegramMessageGateway gateway;

    @InjectMocks
    private AddItemsService service;

    private User user;
    private TaskList list;

    @BeforeEach
    void setUp() {
        user = User.create(CHAT_ID, "tester", ZoneId.of("Europe/Kyiv"), "en");
        user.setId(UUID.randomUUID());
        list = TaskList.of(user, "Groceries", ListType.CHECKLIST);
        list.setId(UUID.randomUUID());
    }

    @Test
    void start_setsAddingState_andSendsThePrompt_whenListActive() {
        when(listService.getActiveById(list.getId())).thenReturn(Optional.of(list));

        final Optional<String> name = service.start(user, LIST_VIEW_MESSAGE_ID, list.getId());

        assertThat(name).contains("Groceries");
        verify(conversationState).setState(user.getId(),
                new ConversationState.AddingItems(list.getId(), LIST_VIEW_MESSAGE_ID));
        verify(gateway).sendMarkdown(eq(CHAT_ID), anyString(), any());
    }

    @Test
    void start_returnsEmpty_andDoesNothing_whenListGone() {
        when(listService.getActiveById(list.getId())).thenReturn(Optional.empty());

        assertThat(service.start(user, LIST_VIEW_MESSAGE_ID, list.getId())).isEmpty();

        verifyNoInteractions(gateway);
        verify(conversationState, never()).setState(any(), any());
    }

    @Test
    void addItems_createsOneTaskPerLine_andRefreshesTheLastPage() {
        when(listService.getActiveById(list.getId())).thenReturn(Optional.of(list));
        final var state = new ConversationState.AddingItems(list.getId(), LIST_VIEW_MESSAGE_ID);

        final Optional<String> reply = service.addItems(user, state, "Milk\n\n  Bread \nEggs");

        assertThat(reply).isEmpty();
        final ArgumentCaptor<List<String>> titles = ArgumentCaptor.captor();
        verify(taskService).createTasks(eq(list.getId()), titles.capture());
        assertThat(titles.getValue()).containsExactly("Milk", "Bread", "Eggs");
        verify(listViewService).showLastPage(user, LIST_VIEW_MESSAGE_ID, list.getId());
    }

    @Test
    void addItems_isSilentAndCreatesNothing_whenMessageIsBlank() {
        when(listService.getActiveById(list.getId())).thenReturn(Optional.of(list));
        final var state = new ConversationState.AddingItems(list.getId(), LIST_VIEW_MESSAGE_ID);

        assertThat(service.addItems(user, state, "   \n  ")).isEmpty();

        verify(taskService, never()).createTasks(any(), any());
        verify(listViewService, never()).showLastPage(any(), anyInt(), any());
    }

    @Test
    void addItems_endsSession_whenListGoneMidFlow() {
        when(listService.getActiveById(list.getId())).thenReturn(Optional.empty());
        final var state = new ConversationState.AddingItems(list.getId(), LIST_VIEW_MESSAGE_ID);

        assertThat(service.addItems(user, state, "Milk")).get().asString().contains("no longer available");

        verify(conversationState).setState(user.getId(), new ConversationState.Idle());
        verify(taskService, never()).createTasks(any(), any());
    }

    @Test
    void finish_done_deletesPrompt_returnsToListView_andToasts() {
        when(listViewService.show(user, LIST_VIEW_MESSAGE_ID, list.getId(), 0))
                .thenReturn(Optional.of("Groceries"));
        final var state = new ConversationState.AddingItems(list.getId(), LIST_VIEW_MESSAGE_ID);

        final Optional<String> toast = service.finish(user, state, PROMPT_MESSAGE_ID, true);

        assertThat(toast).get().asString().contains("added");
        verify(gateway).deleteMessage(CHAT_ID, PROMPT_MESSAGE_ID);
        verify(listViewService).show(user, LIST_VIEW_MESSAGE_ID, list.getId(), 0);
    }

    @Test
    void finish_cancel_stillDeletesPrompt_andReturnsToView() {
        when(listViewService.show(user, LIST_VIEW_MESSAGE_ID, list.getId(), 0))
                .thenReturn(Optional.of("Groceries"));
        final var state = new ConversationState.AddingItems(list.getId(), LIST_VIEW_MESSAGE_ID);

        final Optional<String> toast = service.finish(user, state, PROMPT_MESSAGE_ID, false);

        assertThat(toast).get().asString().contains("Stopped");
        verify(gateway).deleteMessage(CHAT_ID, PROMPT_MESSAGE_ID);
    }

    @Test
    void finish_reportsListGone_andClearsState_whenViewCannotRender() {
        when(listViewService.show(user, LIST_VIEW_MESSAGE_ID, list.getId(), 0)).thenReturn(Optional.empty());
        final var state = new ConversationState.AddingItems(list.getId(), LIST_VIEW_MESSAGE_ID);

        assertThat(service.finish(user, state, PROMPT_MESSAGE_ID, true))
                .get().asString().contains("no longer available");

        verify(gateway).deleteMessage(CHAT_ID, PROMPT_MESSAGE_ID);
        verify(conversationState).setState(user.getId(), new ConversationState.Idle());
    }
}
