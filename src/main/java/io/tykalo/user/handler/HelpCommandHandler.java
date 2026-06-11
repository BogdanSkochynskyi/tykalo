package io.tykalo.user.handler;

import io.tykalo.telegram.TelegramCommand;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Update;

/**
 * The {@code /help} command: a static, grouped catalogue of every command the bot understands, each
 * with a one-line description and an example.
 *
 * <p>The reply travels the plain {@code String} path (see {@code TykaloBot.send}), which sends with no
 * {@code parse_mode}, so the text is laid out as readable plain text with emoji section headers to match
 * every other handler — MarkdownV2 markup would render as literal characters here.
 *
 * <p>NOTE: inline buttons to the Mini App heatmap arrive with TK-233; until then {@code /help} is
 * text-only, consistent with the rest of the command surface.
 */
@Component
public class HelpCommandHandler {

    private static final String HELP = """
            🤖 Tykalo — your task & list bot. Here's everything I can do:

            📋 Lists
            /lists — show your lists
            /list create <name> [type] — create a list (CHECKLIST, ROUTINE or PROJECT)
              e.g. /list create Groceries CHECKLIST
            /list delete <name> — archive a list (asks to confirm)
            /use <name> — switch the current list; new /add tasks land there
              e.g. /use Groceries

            ✅ Tasks
            /add <title> — add a task to the current list; a leading date or repeat keyword is parsed out
              e.g. /add buy milk tomorrow 9am
            /today — tasks due today
            /overdue — tasks past their due date
            /week — tasks due over the next 7 days
            /done <id> — mark a task done
            /edit <id> <field> <value> — change title, description, due or priority
              e.g. /edit <id> due tomorrow 9am
            /snooze <id> <duration> — push a deadline (1h, 2d, tomorrow, next week)
              e.g. /snooze <id> 2d
            /delete <id> — archive a task (asks to confirm)

            🔔 Nudgers
            /nudgers list — show your active Nudgers
            /nudgers add @username — invite a trusted contact to nudge you
            /nudgers remove @username — drop a Nudger (asks to confirm)
            /nudgers pause @username — stop escalations to them
            /nudgers resume @username — let escalations reach them again
            /task <id> nudgers @user1 @user2 — pick who escalates a Project task (or `off` to keep it private)

            ⏰ Scheduling
            /morning <hour> — daily morning digest of the day's tasks
              e.g. /morning 8:00, or /morning off
            (Overdue Project tasks escalate to your Nudgers automatically.)

            ⚙️ Settings
            /tz <IANA> — set your timezone
              e.g. /tz Europe/Kyiv
            /quiet HH:MM-HH:MM — quiet hours when I won't message you
              e.g. /quiet 22:00-07:00, or /quiet off
            /start — welcome message and intro
            /help — this message""";

    @TelegramCommand("/help")
    public String help(final Update update) {
        return HELP;
    }
}
