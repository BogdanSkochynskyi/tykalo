package io.tykalo.menu;

import io.tykalo.list.ListRenderer;
import io.tykalo.list.ListService;
import io.tykalo.list.ListType;
import io.tykalo.list.TaskList;
import io.tykalo.list.TaskService;
import io.tykalo.telegram.TelegramMessageGateway;
import io.tykalo.telegram.conversation.ConversationState;
import io.tykalo.telegram.conversation.ConversationStateService;
import io.tykalo.user.User;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

/**
 * Renders the "My Lists" screen (TK-182): the user's active lists as a navigable inline keyboard,
 * each row a tappable list, with a bottom row to create a list or go back to the main menu and a
 * pager once there are more than {@link #PAGE_SIZE} lists. Showing it sets the user's
 * {@link ConversationState} to {@link ConversationState.Lists}.
 *
 * <p>Two entry points share one renderer: {@link #open(User)} sends a new message (the {@code /lists}
 * command), while {@link #navigate(User, int, int)} edits an existing message in place (arriving from
 * the main menu or paging). When the only list is the auto-provisioned Inbox the screen shows a
 * prompt to create a first list instead of an empty grid.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MyListsService {

    public static final String OPEN_PREFIX = "lists:open:";
    public static final String PAGE_PREFIX = "lists:page:";
    public static final String NEW = "lists:new";
    public static final String BACK = "lists:menu";

    static final int PAGE_SIZE = 8;

    private final ListService listService;
    private final TaskService taskService;
    private final ConversationStateService conversationState;
    private final TelegramMessageGateway gateway;

    /** Opens the My Lists screen as a new message — the {@code /lists} command. */
    public void open(final User user) {
        conversationState.setState(user.getId(), new ConversationState.Lists());
        final Screen screen = render(user, 0);
        gateway.sendMarkdown(user.getTgChatId(), screen.text(), screen.keyboard());
        log.debug("Opened My Lists for user id={}", user.getId());
    }

    /** Renders the My Lists screen into an existing message — main-menu navigation or paging. */
    public void navigate(final User user, final int messageId, final int page) {
        conversationState.setState(user.getId(), new ConversationState.Lists());
        final Screen screen = render(user, page);
        gateway.editMarkdown(user.getTgChatId(), messageId, screen.text(), screen.keyboard());
        log.debug("Navigated user id={} to My Lists page {}", user.getId(), page);
    }

    private Screen render(final User user, final int requestedPage) {
        final List<TaskList> lists = activeLists(Objects.requireNonNull(user.getId()));
        if (lists.stream().noneMatch(list -> list.getType() != ListType.INBOX)) {
            return emptyScreen();
        }
        final int pageCount = Math.max(1, (lists.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        final int page = Math.clamp(requestedPage, 0, pageCount - 1);
        final int from = page * PAGE_SIZE;
        final List<TaskList> pageLists = lists.subList(from, Math.min(from + PAGE_SIZE, lists.size()));
        return new Screen(body(pageLists), keyboard(pageLists, page, pageCount));
    }

    private List<TaskList> activeLists(final UUID ownerId) {
        final List<TaskList> lists = new ArrayList<>(listService.findAllByOwner(ownerId));
        lists.sort(Comparator.comparing(TaskList::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())));
        return lists;
    }

    private String body(final List<TaskList> lists) {
        final StringBuilder out = new StringBuilder("📋 My Lists\n");
        for (final TaskList list : lists) {
            final TaskService.Counts counts = taskService.counts(Objects.requireNonNull(list.getId()));
            out.append("\n%s %s — %d items (%d done)".formatted(
                    icon(list.getType()), list.getName(), counts.total(), counts.done()));
        }
        return ListRenderer.escape(out.toString());
    }

    private Screen emptyScreen() {
        final String text = ListRenderer.escape("📋 My Lists\n\nYou have only Inbox. Create your first list!");
        return new Screen(text, InlineKeyboardMarkup.builder().keyboard(List.of(bottomRow())).build());
    }

    private InlineKeyboardMarkup keyboard(final List<TaskList> lists, final int page, final int pageCount) {
        final List<InlineKeyboardRow> rows = new ArrayList<>();
        InlineKeyboardRow current = new InlineKeyboardRow();
        for (final TaskList list : lists) {
            current.add(button("%s %s".formatted(icon(list.getType()), list.getName()),
                    OPEN_PREFIX + list.getId()));
            if (current.size() == 2) {
                rows.add(current);
                current = new InlineKeyboardRow();
            }
        }
        if (!current.isEmpty()) {
            rows.add(current);
        }
        if (pageCount > 1) {
            rows.add(paginationRow(page, pageCount));
        }
        rows.add(bottomRow());
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    private InlineKeyboardRow paginationRow(final int page, final int pageCount) {
        final InlineKeyboardRow row = new InlineKeyboardRow();
        if (page > 0) {
            row.add(button("⬅️ Prev", PAGE_PREFIX + (page - 1)));
        }
        if (page < pageCount - 1) {
            row.add(button("Next ➡️", PAGE_PREFIX + (page + 1)));
        }
        return row;
    }

    private InlineKeyboardRow bottomRow() {
        final InlineKeyboardRow row = new InlineKeyboardRow();
        row.add(button("➕ New list", NEW));
        row.add(button("⬅️ Back to menu", BACK));
        return row;
    }

    private String icon(final ListType type) {
        return ListIcons.of(type);
    }

    private InlineKeyboardButton button(final String text, final String callbackData) {
        return InlineKeyboardButton.builder().text(text).callbackData(callbackData).build();
    }

    private record Screen(String text, InlineKeyboardMarkup keyboard) {
    }
}
