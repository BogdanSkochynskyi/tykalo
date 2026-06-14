package io.tykalo.menu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.tykalo.list.ListService;
import io.tykalo.list.ListType;
import io.tykalo.list.TaskList;
import io.tykalo.telegram.TelegramMessageGateway;
import io.tykalo.telegram.conversation.ConversationState;
import io.tykalo.telegram.conversation.ConversationStateService;
import io.tykalo.user.User;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CreateListServiceTest {

    private static final long CHAT_ID = 321L;
    private static final int MESSAGE_ID = 12;

    @Mock
    private ListService listService;

    @Mock
    private ConversationStateService conversationState;

    @Mock
    private ListViewService listViewService;

    @Mock
    private MenuService menuService;

    @Mock
    private TelegramMessageGateway gateway;

    @InjectMocks
    private CreateListService service;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.create(CHAT_ID, "tester", ZoneId.of("Europe/Kyiv"), "en");
        user.setId(UUID.randomUUID());
    }

    @Test
    void start_setsTypeState_andEditsMessageIntoTheTypePicker() {
        service.start(user, MESSAGE_ID);

        verify(conversationState).setState(user.getId(), new ConversationState.CreatingListType());
        verify(gateway).editMarkdown(eq(CHAT_ID), eq(MESSAGE_ID), anyString(), any());
    }

    @Test
    void chooseType_setsNamingState_withThePromptId_andEditsIntoTheNamePrompt() {
        service.chooseType(user, MESSAGE_ID, ListType.PROJECT);

        verify(conversationState).setState(user.getId(),
                new ConversationState.CreatingListName(ListType.PROJECT, MESSAGE_ID));
        verify(gateway).editMarkdown(eq(CHAT_ID), eq(MESSAGE_ID), anyString(), any());
    }

    @Test
    void submitName_createsTheList_andOpensItsView_onAValidName() {
        final TaskList created = TaskList.of(user, "Groceries", ListType.CHECKLIST);
        created.setId(UUID.randomUUID());
        when(listService.findActiveByName(user.getId(), "Groceries")).thenReturn(Optional.empty());
        when(listService.createList(user, "Groceries", ListType.CHECKLIST)).thenReturn(created);
        final var state = new ConversationState.CreatingListName(ListType.CHECKLIST, MESSAGE_ID);

        final Optional<String> reply = service.submitName(user, state, "Groceries");

        assertThat(reply).isEmpty();
        verify(listService).createList(user, "Groceries", ListType.CHECKLIST);
        verify(listViewService).show(user, MESSAGE_ID, created.getId(), 0);
    }

    @Test
    void submitName_stripsTheName_beforeCreating() {
        final TaskList created = TaskList.of(user, "Groceries", ListType.CHECKLIST);
        created.setId(UUID.randomUUID());
        when(listService.findActiveByName(user.getId(), "Groceries")).thenReturn(Optional.empty());
        when(listService.createList(user, "Groceries", ListType.CHECKLIST)).thenReturn(created);
        final var state = new ConversationState.CreatingListName(ListType.CHECKLIST, MESSAGE_ID);

        service.submitName(user, state, "  Groceries  ");

        verify(listService).createList(user, "Groceries", ListType.CHECKLIST);
    }

    @Test
    void submitName_repromptsInPlace_andCreatesNothing_whenNameIsBlank() {
        final var state = new ConversationState.CreatingListName(ListType.CHECKLIST, MESSAGE_ID);

        assertThat(service.submitName(user, state, "   ")).isEmpty();

        verify(gateway).editMarkdown(eq(CHAT_ID), eq(MESSAGE_ID), anyString(), any());
        verify(listService, never()).createList(any(), anyString(), any());
        verifyNoInteractions(listViewService);
    }

    @Test
    void submitName_repromptsInPlace_andCreatesNothing_whenNameIsDuplicate() {
        final TaskList existing = TaskList.of(user, "Groceries", ListType.CHECKLIST);
        when(listService.findActiveByName(user.getId(), "Groceries")).thenReturn(Optional.of(existing));
        final var state = new ConversationState.CreatingListName(ListType.PROJECT, MESSAGE_ID);

        assertThat(service.submitName(user, state, "Groceries")).isEmpty();

        verify(gateway).editMarkdown(eq(CHAT_ID), eq(MESSAGE_ID), anyString(), any());
        verify(listService, never()).createList(any(), anyString(), any());
        verifyNoInteractions(listViewService);
    }

    @Test
    void cancel_returnsToTheMainMenuInPlace() {
        final Optional<String> toast = service.cancel(user, MESSAGE_ID);

        assertThat(toast).get().asString().contains("Cancelled");
        verify(menuService).editToMainMenu(user, MESSAGE_ID);
        verify(listService, never()).createList(any(), anyString(), any());
    }

    @Test
    void start_doesNotTouchListView_orCreateAnything() {
        service.start(user, MESSAGE_ID);

        verifyNoInteractions(listViewService);
        verify(listService, never()).createList(any(), anyString(), any());
    }
}
