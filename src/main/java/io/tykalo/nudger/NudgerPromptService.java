package io.tykalo.nudger;

import io.tykalo.list.ListRenderer;
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
 * Sends the nudger consent prompt (TK-153) — the single source of truth for the Yes/No request, used
 * by both invite entry points ({@code /nudgers add} for a registered invitee and the {@code /start}
 * deep-link). The Yes/No buttons can't ride the plain {@code String} reply path, so the prompt always
 * goes out through {@link TelegramMessageGateway}, its {@code nudger:accept:{id}} /
 * {@code nudger:decline:{id}} callbacks landing on {@code NudgerConsentCallbackHandler}.
 */
@Service
@RequiredArgsConstructor
public class NudgerPromptService {

    public static final String ACCEPT_PREFIX = "nudger:accept:";
    public static final String DECLINE_PREFIX = "nudger:decline:";

    private final TelegramMessageGateway gateway;

    /** Delivers the consent prompt for {@code nudger} to {@code invitee}, naming {@code owner}. */
    public void sendConsentPrompt(final Nudger nudger, final User invitee, final User owner) {
        final UUID nudgerId = Objects.requireNonNull(nudger.getId(), "nudger must be persisted before prompting");
        final String ownerName = owner.getTgUsername() == null ? "Someone" : "@" + owner.getTgUsername();
        final String text = ("🔔 %s invites you to be their Nudger. You'd get the occasional request to "
                + "remind them about an overdue task — first just the task number, then its title, then the "
                + "full details. Agree?").formatted(ownerName);
        gateway.sendMarkdown(invitee.getTgChatId(), ListRenderer.escape(text), consentKeyboard(nudgerId));
    }

    private InlineKeyboardMarkup consentKeyboard(final UUID nudgerId) {
        final InlineKeyboardRow row = new InlineKeyboardRow();
        row.add(InlineKeyboardButton.builder().text("✅ Yes").callbackData(ACCEPT_PREFIX + nudgerId).build());
        row.add(InlineKeyboardButton.builder().text("❌ No").callbackData(DECLINE_PREFIX + nudgerId).build());
        return InlineKeyboardMarkup.builder().keyboard(List.of(row)).build();
    }
}
