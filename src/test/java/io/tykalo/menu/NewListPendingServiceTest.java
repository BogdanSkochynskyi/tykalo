package io.tykalo.menu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.tykalo.list.ListService;
import io.tykalo.list.ListType;
import io.tykalo.list.PendingItem;
import io.tykalo.list.PendingItemService;
import io.tykalo.list.TaskList;
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
class NewListPendingServiceTest {

    private static final long CHAT_ID = 55L;
    private static final int MESSAGE_ID = 8;

    @Mock
    private ListService listService;

    @Mock
    private PendingItemService pendingItemService;

    @Mock
    private ConversationStateService conversationState;

    @Mock
    private ListViewService listViewService;

    @Mock
    private TelegramMessageGateway gateway;

    @InjectMocks
    private NewListPendingService service;

    private User user;
    private TaskList list;
    private PendingItem itemA;
    private PendingItem itemB;

    @BeforeEach
    void setUp() {
        user = User.create(CHAT_ID, "tester", ZoneId.of("Europe/Kyiv"), "en");
        user.setId(UUID.randomUUID());
        list = TaskList.of(user, "Groceries", ListType.CHECKLIST);
        list.setId(UUID.randomUUID());
        list.getTags().add("groceries");
        itemA = pendingItem("Milk");
        itemB = pendingItem("Bread");
    }

    @Test
    void present_selectsEverything_andRendersTheScreen() {
        service.present(user, MESSAGE_ID, list, List.of(itemA, itemB));

        verify(conversationState).setState(user.getId(), new ConversationState.CreatingListPendingCheck(
                list.getId(), List.of(itemA.getId(), itemB.getId())));
        verify(gateway).editMarkdown(eq(CHAT_ID), eq(MESSAGE_ID), anyString(), any());
    }

    @Test
    void toggle_deselectsAnAlreadySelectedItem_andReRenders() {
        when(listService.getActiveById(list.getId())).thenReturn(Optional.of(list));
        when(pendingItemService.findByTags(eq(user.getId()), any())).thenReturn(List.of(itemA, itemB));
        final var state = new ConversationState.CreatingListPendingCheck(
                list.getId(), List.of(itemA.getId(), itemB.getId()));

        assertThat(service.toggle(user, MESSAGE_ID, state, itemA.getId())).isEmpty();

        verify(conversationState).setState(user.getId(), new ConversationState.CreatingListPendingCheck(
                list.getId(), List.of(itemB.getId())));
        verify(gateway).editMarkdown(eq(CHAT_ID), eq(MESSAGE_ID), anyString(), any());
    }

    @Test
    void toggle_reselectsADeselectedItem() {
        when(listService.getActiveById(list.getId())).thenReturn(Optional.of(list));
        when(pendingItemService.findByTags(eq(user.getId()), any())).thenReturn(List.of(itemA, itemB));
        final var state = new ConversationState.CreatingListPendingCheck(list.getId(), List.of(itemB.getId()));

        service.toggle(user, MESSAGE_ID, state, itemA.getId());

        final ArgumentCaptor<ConversationState> captor = ArgumentCaptor.forClass(ConversationState.class);
        verify(conversationState).setState(eq(user.getId()), captor.capture());
        final var next = (ConversationState.CreatingListPendingCheck) captor.getValue();
        assertThat(next.selectedIds()).containsExactlyInAnyOrder(itemA.getId(), itemB.getId());
    }

    @Test
    void addSelected_restoresOnlySelectedLiveItems_thenOpensTheList() {
        when(listService.getActiveById(list.getId())).thenReturn(Optional.of(list));
        when(pendingItemService.findByTags(eq(user.getId()), any())).thenReturn(List.of(itemA, itemB));
        when(listViewService.show(eq(user), eq(MESSAGE_ID), eq(list.getId()), anyInt()))
                .thenReturn(Optional.of("Groceries"));
        final var state = new ConversationState.CreatingListPendingCheck(list.getId(), List.of(itemA.getId()));

        final Optional<String> toast = service.addSelected(user, MESSAGE_ID, state);

        assertThat(toast).get().asString().contains("Added 1");
        verify(pendingItemService).restore(itemA.getId(), list.getId());
        verify(pendingItemService, never()).restore(eq(itemB.getId()), any());
        verify(listViewService).show(user, MESSAGE_ID, list.getId(), 0);
    }

    @Test
    void dropSelected_deletesOnlySelectedLiveItems_thenOpensTheList() {
        when(listService.getActiveById(list.getId())).thenReturn(Optional.of(list));
        when(pendingItemService.findByTags(eq(user.getId()), any())).thenReturn(List.of(itemA, itemB));
        when(listViewService.show(eq(user), eq(MESSAGE_ID), eq(list.getId()), anyInt()))
                .thenReturn(Optional.of("Groceries"));
        final var state = new ConversationState.CreatingListPendingCheck(
                list.getId(), List.of(itemA.getId(), itemB.getId()));

        final Optional<String> toast = service.dropSelected(user, MESSAGE_ID, state);

        assertThat(toast).get().asString().contains("Dropped 2");
        verify(pendingItemService).delete(itemA.getId());
        verify(pendingItemService).delete(itemB.getId());
        verify(pendingItemService, never()).restore(any(), any());
        verify(listViewService).show(user, MESSAGE_ID, list.getId(), 0);
    }

    @Test
    void skip_leavesPendingUntouched_andOpensTheList() {
        when(listViewService.show(eq(user), eq(MESSAGE_ID), eq(list.getId()), anyInt()))
                .thenReturn(Optional.of("Groceries"));
        final var state = new ConversationState.CreatingListPendingCheck(list.getId(), List.of(itemA.getId()));

        assertThat(service.skip(user, MESSAGE_ID, state)).isEmpty();

        verify(pendingItemService, never()).restore(any(), any());
        verify(pendingItemService, never()).delete(any());
        verify(listViewService).show(user, MESSAGE_ID, list.getId(), 0);
    }

    @Test
    void addSelected_whenTheListVanished_justReportsItIsGone() {
        when(listService.getActiveById(list.getId())).thenReturn(Optional.empty());
        when(listViewService.show(eq(user), eq(MESSAGE_ID), eq(list.getId()), anyInt()))
                .thenReturn(Optional.empty());
        final var state = new ConversationState.CreatingListPendingCheck(list.getId(), List.of(itemA.getId()));

        assertThat(service.addSelected(user, MESSAGE_ID, state)).get().asString().contains("no longer available");
        verify(pendingItemService, never()).restore(any(), any());
    }

    private PendingItem pendingItem(final String title) {
        final PendingItem item = new PendingItem();
        item.setId(UUID.randomUUID());
        item.setTitle(title);
        return item;
    }
}
