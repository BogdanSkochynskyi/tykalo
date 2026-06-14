package io.tykalo.list;

import io.tykalo.telegram.TelegramMessageGateway;
import io.tykalo.user.User;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

/**
 * Sends the shared-list invitation prompt (TK-193) — the single source of truth for the Yes/No request,
 * used by both invite entry points (an {@code @username} invite and the {@code /start} share-link). The
 * Yes/No buttons can't ride the plain {@code String} reply path, so the prompt always goes out through
 * {@link TelegramMessageGateway}, its {@code lm:accept:{id}} / {@code lm:decline:{id}} callbacks landing
 * on {@code ListInviteCallbackHandler}.
 */
@Service
@RequiredArgsConstructor
public class ListInvitePromptService {

    public static final String ACCEPT_PREFIX = "lm:accept:";
    public static final String DECLINE_PREFIX = "lm:decline:";

    private final TelegramMessageGateway gateway;

    /** Delivers the invite prompt for {@code member} to {@code invitee}, naming {@code inviter} and {@code list}. */
    public void sendInvitePrompt(final ListMember member, final User invitee, final User inviter,
            final TaskList list) {
        final UUID memberId = Objects.requireNonNull(member.getId(), "member must be persisted before prompting");
        final String inviterName = inviter.getTgUsername() == null ? "Someone" : "@" + inviter.getTgUsername();
        final String text = "🔔 %s invites you to join the list \"%s\" as %s. Accept?"
                .formatted(inviterName, list.getName(), member.getRole());
        gateway.sendMarkdown(invitee.getTgChatId(), ListRenderer.escape(text), inviteKeyboard(memberId));
    }

    private InlineKeyboardMarkup inviteKeyboard(final UUID memberId) {
        final InlineKeyboardRow row = new InlineKeyboardRow();
        row.add(InlineKeyboardButton.builder().text("✅ Yes").callbackData(ACCEPT_PREFIX + memberId).build());
        row.add(InlineKeyboardButton.builder().text("❌ No").callbackData(DECLINE_PREFIX + memberId).build());
        return InlineKeyboardMarkup.builder().keyboard(List.of(row)).build();
    }
}
