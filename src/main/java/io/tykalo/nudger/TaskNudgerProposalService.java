package io.tykalo.nudger;

import io.tykalo.list.ListRenderer;
import io.tykalo.list.ListType;
import io.tykalo.list.Task;
import io.tykalo.list.TaskCreatedEvent;
import io.tykalo.telegram.CompactUuid;
import io.tykalo.telegram.TelegramMessageGateway;
import io.tykalo.user.User;
import io.tykalo.user.UserRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

/**
 * Proposes a per-task Nudger choice when a Project task is created (TK-158). Listening to the same
 * {@link TaskCreatedEvent} as {@link EscalationPolicyService}, it sends the owner an inline picker —
 * one button per active Nudger, plus "Keep private" and "Use all" — when (and only when) the owner has
 * active Nudgers to choose from. The taps land on {@code TaskNudgerCallbackHandler} via
 * {@link CompactUuid}-packed callbacks, since two ids would otherwise overflow Telegram's 64-byte limit.
 *
 * <p>A brand-new task never has an assignment yet, so the prompt's "if no assigned" condition reduces
 * to "is a Project task with at least one active Nudger". Non-Project tasks and owners with no active
 * Nudgers get nothing.
 */
@Service
@RequiredArgsConstructor
public class TaskNudgerProposalService {

    public static final String ASSIGN_PREFIX = "tn:a:";
    public static final String PRIVATE_PREFIX = "tn:p:";
    public static final String DEFAULT_PREFIX = "tn:u:";

    private final NudgerRepository nudgerRepository;
    private final UserRepository userRepository;
    private final TelegramMessageGateway gateway;

    @EventListener
    public void onTaskCreated(final TaskCreatedEvent event) {
        if (event.listType() == ListType.PROJECT) {
            propose(event.task());
        }
    }

    private void propose(final Task task) {
        final UUID taskId = Objects.requireNonNull(task.getId(), "task must be persisted before proposing");
        final List<Nudger> active = nudgerRepository.findByOwnerIdAndStatus(task.getOwnerId(), NudgerStatus.ACTIVE);
        if (active.isEmpty()) {
            return;
        }
        final User owner = userRepository.findById(task.getOwnerId()).orElse(null);
        if (owner == null) {
            return;
        }
        final Map<UUID, String> usernames = userRepository
                .findAllById(active.stream().map(Nudger::getNudgerUserId).toList()).stream()
                .collect(Collectors.toMap(User::getId, User::getTgUsername));
        final String text = "📌 New Project task \"%s\". Who should nudge you if it slips?".formatted(task.getTitle());
        gateway.sendMarkdown(owner.getTgChatId(), ListRenderer.escape(text), pickerKeyboard(taskId, active, usernames));
    }

    private InlineKeyboardMarkup pickerKeyboard(final UUID taskId, final List<Nudger> active,
                                                final Map<UUID, String> usernames) {
        final String encodedTask = CompactUuid.encode(taskId);
        final List<InlineKeyboardRow> rows = new ArrayList<>();
        for (final Nudger nudger : active) {
            final String username = usernames.getOrDefault(nudger.getNudgerUserId(), "?");
            rows.add(row(button("👤 @" + username,
                    ASSIGN_PREFIX + encodedTask + ":" + CompactUuid.encode(Objects.requireNonNull(nudger.getId())))));
        }
        final InlineKeyboardRow footer = new InlineKeyboardRow();
        footer.add(button("🔒 Keep private", PRIVATE_PREFIX + encodedTask));
        footer.add(button("👥 Use all", DEFAULT_PREFIX + encodedTask));
        rows.add(footer);
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    private static InlineKeyboardRow row(final InlineKeyboardButton button) {
        final InlineKeyboardRow row = new InlineKeyboardRow();
        row.add(button);
        return row;
    }

    private static InlineKeyboardButton button(final String text, final String callbackData) {
        return InlineKeyboardButton.builder().text(text).callbackData(callbackData).build();
    }
}
