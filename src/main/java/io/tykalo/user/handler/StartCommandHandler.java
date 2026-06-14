package io.tykalo.user.handler;

import io.tykalo.list.DeepLinkInviteResult;
import io.tykalo.list.ListInvite;
import io.tykalo.list.ListInvitePromptService;
import io.tykalo.list.ListInviteService;
import io.tykalo.menu.MenuService;
import io.tykalo.nudger.AcceptResult;
import io.tykalo.nudger.NudgeInvite;
import io.tykalo.nudger.NudgerPromptService;
import io.tykalo.nudger.NudgerService;
import io.tykalo.onboarding.OnboardingService;
import io.tykalo.telegram.TelegramCommand;
import io.tykalo.user.User;
import io.tykalo.user.UserService;
import io.tykalo.user.UserService.Registration;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;

/**
 * Handles {@code /start}: registers the user on first contact and greets them. A {@code /start
 * nudge_invite_<payload>} carries a nudger invite deep-link (TK-152) — the new user is wired up as
 * the encoded owner's pending nudger, the greeting notes who invited them, and the Yes/No consent
 * prompt (TK-153) is sent as a follow-up message (its buttons can't ride the plain string reply).
 *
 * <p>A genuine first {@code /start} with no invite kicks off the 3-step onboarding (TK-172): the
 * {@link OnboardingService} owns the messages from there, so this handler stays silent. Invited
 * users skip onboarding — their greeting + consent prompt take over instead; this covers both a Nudger
 * invite ({@code nudge_invite_…}) and a shared-list invite ({@code list_invite_…}, TK-193), each
 * carrying its own deep-link payload. A returning user gets the main menu (TK-181) via
 * {@link MenuService} rather than a text-only welcome, so the handler is again silent on that path.
 */
@Component
@RequiredArgsConstructor
public class StartCommandHandler {

    private final UserService userService;
    private final NudgerService nudgerService;
    private final NudgerPromptService promptService;
    private final ListInviteService listInviteService;
    private final ListInvitePromptService listInvitePromptService;
    private final OnboardingService onboardingService;
    private final MenuService menuService;

    @TelegramCommand("/start")
    public @Nullable String start(final Update update) {
        final Registration registration = userService.register(update);
        final User user = registration.user();
        final String payload = startPayload(update);
        final Optional<String> listNote = listInviteNote(payload, user);
        if (listNote.isPresent()) {
            return greeting(user) + listNote.get();
        }
        final String nudgeNote = nudgeInviteNote(payload, user);
        if (!nudgeNote.isEmpty()) {
            return greeting(user) + nudgeNote;
        }
        if (registration.created()) {
            onboardingService.begin(user);
            return null;
        }
        menuService.showMainMenu(user);
        return null;
    }

    private Optional<String> listInviteNote(final String payload, final User invitee) {
        final Optional<ListInvite.Payload> parsed = ListInvite.parse(payload);
        if (parsed.isEmpty()) {
            return Optional.empty();
        }
        final DeepLinkInviteResult result = listInviteService.acceptViaDeepLink(invitee, parsed.get());
        return switch (result) {
            case DeepLinkInviteResult.Invited invited -> {
                listInvitePromptService.sendInvitePrompt(invited.member(), invitee, invited.inviter(), invited.list());
                yield Optional.of(listInvitedNote(invited.inviter(), invited.list().getName()));
            }
            case DeepLinkInviteResult.AlreadyPending pending -> {
                listInvitePromptService.sendInvitePrompt(pending.member(), invitee, pending.inviter(), pending.list());
                yield Optional.of(listInvitedNote(pending.inviter(), pending.list().getName()));
            }
            case DeepLinkInviteResult.AlreadyMember member ->
                    Optional.of("\n\nYou're already a member of \"%s\".".formatted(member.list().getName()));
            case DeepLinkInviteResult.Expired ignored ->
                    Optional.of("\n\n⚠️ This invite link has expired — ask for a fresh one.");
            case DeepLinkInviteResult.SelfInvite ignored -> Optional.empty();
            case DeepLinkInviteResult.Unavailable ignored -> Optional.empty();
        };
    }

    private String listInvitedNote(final User inviter, final String listName) {
        final String name = inviter.getTgUsername() == null ? "Someone" : "@" + inviter.getTgUsername();
        return "\n\n🔔 %s invited you to join the list \"%s\" — please accept or decline below."
                .formatted(name, listName);
    }

    private String nudgeInviteNote(final String payload, final User invitee) {
        final Optional<UUID> ownerId = NudgeInvite.parse(payload);
        if (ownerId.isEmpty()) {
            return "";
        }
        final AcceptResult result = nudgerService.acceptViaDeepLink(invitee, ownerId.get());
        return switch (result) {
            case AcceptResult.Invited invited -> {
                promptService.sendConsentPrompt(invited.nudger(), invitee, invited.owner());
                yield invitedNote(invited.owner());
            }
            case AcceptResult.AlreadyInvited already -> invitedNote(already.owner());
            case AcceptResult.SelfInvite ignored -> "";
            case AcceptResult.OwnerGone ignored -> "";
        };
    }

    private String invitedNote(final User owner) {
        final String name = owner.getTgUsername() == null ? "Someone" : "@" + owner.getTgUsername();
        return "\n\n🔔 %s invited you to be their Nudger — please accept or decline below.".formatted(name);
    }

    private String greeting(final User user) {
        final String name = user.getTgUsername() == null ? "there" : "@" + user.getTgUsername();
        return """
                👋 Hi, %s! Welcome to Tykalo — your personal task & list bot.

                I help you capture tasks and, when you ask, nudge you (and your trusted contacts) \
                until they get done.

                Your timezone is set to %s — change it any time with /tz.
                Send /help to see everything I can do.""".formatted(name, user.getTimezone());
    }

    private String startPayload(final Update update) {
        final Message message = update.getMessage();
        final String text = message == null ? null : message.getText();
        if (text == null) {
            return "";
        }
        final String[] parts = text.strip().split("\\s+", 2);
        return parts.length > 1 ? parts[1].strip() : "";
    }
}
