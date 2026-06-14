package io.tykalo.menu;

import java.util.Optional;

/**
 * The help categories shown on the help screen (TK-189). Each topic carries the label used on its
 * top-level button and the plain-text body listing the commands in that category — the body is the
 * grouped command catalogue that used to live in one block in the old text-only {@code /help} (TK-171),
 * split per category so each drilldown shows only its own commands.
 *
 * <p>The enum {@code name()} doubles as the stable token packed into the {@code help:cat:{TOPIC}}
 * {@code callback_data} and persisted in {@link io.tykalo.telegram.conversation.ConversationState}.
 */
public enum HelpTopic {

    LISTS("📋 Lists & tasks", """
            📋 Lists & tasks

            /menu — open the menu to browse lists and settings
            /lists — show your lists
            /list create <name> [type] — create a list (CHECKLIST, ROUTINE or PROJECT)
            /list delete <name> — archive a list (asks to confirm)
            /use <name> — switch the current list; new /add tasks land there

            /add <title> — add a task to the current list; a leading date or repeat keyword is parsed out
              e.g. /add buy milk tomorrow 9am
            /today — tasks due today
            /overdue — tasks past their due date
            /week — tasks due over the next 7 days
            /done <id> — mark a task done
            /edit <id> <field> <value> — change title, description, due or priority
            /snooze <id> <duration> — push a deadline (1h, 2d, tomorrow, next week)
            /delete <id> — archive a task (asks to confirm)"""),

    NUDGERS("🔔 Nudgers", """
            🔔 Nudgers

            Nudgers are trusted contacts who get pinged when your tasks slip — first the task number,
            then its title, then the full description.

            /nudgers list — show your active Nudgers
            /nudgers add @username — invite a trusted contact to nudge you
            /nudgers remove @username — drop a Nudger (asks to confirm)
            /nudgers pause @username — stop escalations to them
            /nudgers resume @username — let escalations reach them again
            /task <id> nudgers @user1 @user2 — pick who escalates a Project task (or `off` to keep it private)"""),

    SCHEDULING("⏰ Scheduling & timezone", """
            ⏰ Scheduling & timezone

            /morning <hour> — daily morning digest of the day's tasks
              e.g. /morning 8:00, or /morning off
            /tz <IANA> — set your timezone
              e.g. /tz Europe/Kyiv

            Overdue Project tasks escalate to your Nudgers automatically."""),

    SETTINGS("⚙️ Settings", """
            ⚙️ Settings

            /quiet HH:MM-HH:MM — quiet hours when I won't message you
              e.g. /quiet 22:00-07:00, or /quiet off
            /start — welcome message and intro
            /help — open this help""");

    private final String label;
    private final String body;

    HelpTopic(final String label, final String body) {
        this.label = label;
        this.body = body;
    }

    public String label() {
        return label;
    }

    public String body() {
        return body;
    }

    /** Parses a {@code callback_data} token back to a topic, empty if it names no known category. */
    public static Optional<HelpTopic> parse(final String raw) {
        try {
            return Optional.of(valueOf(raw));
        } catch (final IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
