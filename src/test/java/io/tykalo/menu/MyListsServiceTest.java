package io.tykalo.menu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

@ExtendWith(MockitoExtension.class)
class MyListsServiceTest {

    private static final long CHAT_ID = 100L;

    @Mock
    private ListService listService;

    @Mock
    private TaskService taskService;

    @Mock
    private ConversationStateService conversationState;

    @Mock
    private TelegramMessageGateway gateway;

    @InjectMocks
    private MyListsService service;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.create(CHAT_ID, "tester", ZoneId.of("Europe/Kyiv"), "en");
        user.setId(UUID.randomUUID());
    }

    private TaskList list(final String name, final ListType type) {
        final TaskList list = TaskList.of(user, name, type);
        list.setId(UUID.randomUUID());
        return list;
    }

    private List<String> callbackData(final InlineKeyboardMarkup keyboard) {
        return keyboard.getKeyboard().stream()
                .flatMap(Collection::stream)
                .map(InlineKeyboardButton::getCallbackData)
                .toList();
    }

    @Test
    void open_sendsNewMessage_listingEachListWithIconAndCounts_andSetsListsState() {
        // Arrange — an Inbox plus one real list
        final TaskList inbox = list("Inbox", ListType.INBOX);
        final TaskList groceries = list("Groceries", ListType.CHECKLIST);
        when(listService.findAllByOwner(user.getId())).thenReturn(List.of(inbox, groceries));
        when(taskService.counts(inbox.getId())).thenReturn(new TaskService.Counts(0, 0));
        when(taskService.counts(groceries.getId())).thenReturn(new TaskService.Counts(5, 2));

        // Act
        service.open(user);

        // Assert
        verify(conversationState).setState(user.getId(), new ConversationState.Lists());
        final ArgumentCaptor<String> text = ArgumentCaptor.captor();
        final ArgumentCaptor<InlineKeyboardMarkup> keyboard = ArgumentCaptor.captor();
        verify(gateway).sendMarkdown(eq(CHAT_ID), text.capture(), keyboard.capture());

        assertThat(text.getValue()).contains("🛒 Groceries").contains("5 items").contains("2 done")
                .contains("📥 Inbox");
        final List<String> data = callbackData(keyboard.getValue());
        assertThat(data).contains(MyListsService.OPEN_PREFIX + groceries.getId(),
                MyListsService.OPEN_PREFIX + inbox.getId(), MyListsService.NEW, MyListsService.BACK);
    }

    @Test
    void open_showsEmptyState_whenOnlyInboxExists() {
        when(listService.findAllByOwner(user.getId())).thenReturn(List.of(list("Inbox", ListType.INBOX)));

        service.open(user);

        final ArgumentCaptor<String> text = ArgumentCaptor.captor();
        final ArgumentCaptor<InlineKeyboardMarkup> keyboard = ArgumentCaptor.captor();
        verify(gateway).sendMarkdown(eq(CHAT_ID), text.capture(), keyboard.capture());
        assertThat(text.getValue()).contains("only Inbox").contains("Create your first list");
        // No list buttons in the empty state — just the New list / Back row.
        assertThat(callbackData(keyboard.getValue())).containsExactly(MyListsService.NEW, MyListsService.BACK);
    }

    @Test
    void navigate_editsInPlace() {
        when(listService.findAllByOwner(user.getId()))
                .thenReturn(List.of(list("Inbox", ListType.INBOX), list("Work", ListType.PROJECT)));
        when(taskService.counts(any())).thenReturn(new TaskService.Counts(0, 0));

        service.navigate(user, 42, 0);

        verify(conversationState).setState(user.getId(), new ConversationState.Lists());
        verify(gateway).editMarkdown(eq(CHAT_ID), eq(42), anyString(), any());
    }

    @Test
    void navigate_firstPage_showsEightLists_andOnlyANextPager() {
        when(listService.findAllByOwner(user.getId())).thenReturn(tenLists());
        when(taskService.counts(any())).thenReturn(new TaskService.Counts(0, 0));

        service.navigate(user, 42, 0);

        final List<String> data = capturedKeyboardData();
        assertThat(data.stream().filter(d -> d.startsWith(MyListsService.OPEN_PREFIX)).count()).isEqualTo(8);
        assertThat(data).contains(MyListsService.PAGE_PREFIX + 1);          // Next → page 1
        assertThat(data).doesNotContain(MyListsService.PAGE_PREFIX + (-1)); // no Prev on the first page
    }

    @Test
    void navigate_lastPage_showsRemainder_andOnlyAPrevPager() {
        when(listService.findAllByOwner(user.getId())).thenReturn(tenLists());
        when(taskService.counts(any())).thenReturn(new TaskService.Counts(0, 0));

        service.navigate(user, 42, 1);

        final List<String> data = capturedKeyboardData();
        assertThat(data.stream().filter(d -> d.startsWith(MyListsService.OPEN_PREFIX)).count()).isEqualTo(2);
        assertThat(data).contains(MyListsService.PAGE_PREFIX + 0); // Prev → page 0
    }

    private List<TaskList> tenLists() {
        final List<TaskList> lists = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            lists.add(list("List " + i, ListType.CHECKLIST));
        }
        return lists;
    }

    private List<String> capturedKeyboardData() {
        final ArgumentCaptor<InlineKeyboardMarkup> keyboard = ArgumentCaptor.captor();
        verify(gateway).editMarkdown(eq(CHAT_ID), eq(42), anyString(), keyboard.capture());
        return callbackData(keyboard.getValue());
    }
}
