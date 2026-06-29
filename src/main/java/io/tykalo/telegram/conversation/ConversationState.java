package io.tykalo.telegram.conversation;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.tykalo.list.ListMemberRole;
import io.tykalo.list.ListType;
import io.tykalo.menu.HelpTopic;
import java.util.List;
import java.util.UUID;

/**
 * Where a user currently is in the menu-driven conversation (TK-187). Modelled as a sealed interface
 * of records so each screen can carry exactly the context it needs (a {@code listId}, a chosen
 * {@link ListType}, …) and {@code switch} over the states stays exhaustive as the menu grows.
 *
 * <p>Persisted as JSON in Redis (see {@link ConversationStateService}). Jackson writes a stable
 * {@code "@type"} discriminator — deliberately not the record's field, which is why
 * {@link CreatingListName} can keep a component literally named {@code type} without clashing — and
 * the discriminator values are explicit UPPER_SNAKE names so the stored shape is decoupled from class
 * names and safe to evolve.
 *
 * <p>{@link #expectsTextInput()} marks the states whose next plain-text message is meaningful input
 * (item titles, a new list name). The dispatcher uses it to route such messages to a state handler
 * instead of the normal message handlers; navigation states (which advance via inline buttons) leave
 * it {@code false}.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "@type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ConversationState.Idle.class, name = "IDLE"),
        @JsonSubTypes.Type(value = ConversationState.MainMenu.class, name = "MAIN_MENU"),
        @JsonSubTypes.Type(value = ConversationState.Lists.class, name = "LISTS"),
        @JsonSubTypes.Type(value = ConversationState.ListView.class, name = "LIST_VIEW"),
        @JsonSubTypes.Type(value = ConversationState.AddingItems.class, name = "ADDING_ITEMS"),
        @JsonSubTypes.Type(value = ConversationState.CreatingListType.class, name = "CREATING_LIST_TYPE"),
        @JsonSubTypes.Type(value = ConversationState.CreatingListName.class, name = "CREATING_LIST_NAME"),
        @JsonSubTypes.Type(value = ConversationState.ListSettings.class, name = "LIST_SETTINGS"),
        @JsonSubTypes.Type(value = ConversationState.RenamingList.class, name = "RENAMING_LIST"),
        @JsonSubTypes.Type(value = ConversationState.Help.class, name = "HELP"),
        @JsonSubTypes.Type(value = ConversationState.HelpCategory.class, name = "HELP_CATEGORY"),
        @JsonSubTypes.Type(value = ConversationState.InvitingMember.class, name = "INVITING_MEMBER"),
        @JsonSubTypes.Type(value = ConversationState.MembersScreen.class, name = "MEMBERS_SCREEN"),
        @JsonSubTypes.Type(value = ConversationState.Settings.class, name = "SETTINGS"),
        @JsonSubTypes.Type(value = ConversationState.ClosingList.class, name = "CLOSING_LIST"),
        @JsonSubTypes.Type(value = ConversationState.ClosingListTarget.class, name = "CLOSING_LIST_TARGET"),
        @JsonSubTypes.Type(value = ConversationState.EditingListTags.class, name = "EDITING_LIST_TAGS"),
        @JsonSubTypes.Type(value = ConversationState.AddingTag.class, name = "ADDING_TAG"),
        @JsonSubTypes.Type(value = ConversationState.CreatingListTags.class, name = "CREATING_LIST_TAGS"),
        @JsonSubTypes.Type(value = ConversationState.CreatingListPendingCheck.class, name = "CREATING_LIST_PENDING_CHECK"),
})
public sealed interface ConversationState {

    /**
     * Whether this state treats the user's next plain-text message as input to consume (rather than
     * as idle chatter to route normally). {@code true} only for the data-entry states.
     */
    default boolean expectsTextInput() {
        return false;
    }

    /** No active flow — the default when nothing is stored. Never persisted (it is the absence of state). */
    record Idle() implements ConversationState {
    }

    /** The main menu screen (TK-181). */
    record MainMenu() implements ConversationState {
    }

    /** The "My Lists" screen (TK-182). */
    record Lists() implements ConversationState {
    }

    /** Viewing a single list (TK-183). */
    record ListView(UUID listId) implements ConversationState {
    }

    /**
     * Adding items to a list one message at a time (TK-184) — consumes plain-text input. Carries the
     * id of the list-view message that opened the flow so each added item can re-render it in place,
     * above the separate prompt message.
     */
    record AddingItems(UUID listId, int listViewMessageId) implements ConversationState {
        @Override
        public boolean expectsTextInput() {
            return true;
        }
    }

    /** Choosing the type of a new list, awaiting an inline-button pick (TK-185). */
    record CreatingListType() implements ConversationState {
    }

    /**
     * Naming a new list of the chosen {@code type} (TK-185) — consumes plain-text input. Carries the id
     * of the prompt message (the one that shows "Name your list:") so the submitted name can re-render
     * that same message in place — as a validation notice on a blank/duplicate name, or as the new
     * list's view (TK-183) on success.
     */
    record CreatingListName(ListType type, int promptMessageId) implements ConversationState {
        @Override
        public boolean expectsTextInput() {
            return true;
        }
    }

    /** A list's settings screen (TK-186). */
    record ListSettings(UUID listId) implements ConversationState {
    }

    /** Renaming an existing list (TK-186) — consumes plain-text input. */
    record RenamingList(UUID listId) implements ConversationState {
        @Override
        public boolean expectsTextInput() {
            return true;
        }
    }

    /** The top-level help screen (TK-189) — intro plus category buttons. */
    record Help() implements ConversationState {
    }

    /** A help category drilldown (TK-189) — the commands in one {@link HelpTopic} plus a Back button. */
    record HelpCategory(HelpTopic topic) implements ConversationState {
    }

    /**
     * Inviting members to a shared list (TK-193) — consumes plain-text input (each message is a
     * {@code @username} to invite as {@code role}). Carries the id of the list-view message that was
     * edited into the invite prompt, so {@code ❌ Cancel} can restore the list view in place.
     */
    record InvitingMember(UUID listId, ListMemberRole role, int messageId) implements ConversationState {
        @Override
        public boolean expectsTextInput() {
            return true;
        }
    }

    /** The members screen of a shared list (TK-194) — navigation only (member list, remove, transfer). */
    record MembersScreen(UUID listId) implements ConversationState {
    }

    /** The settings screen (TK-196) — navigation only (notification preference radio buttons). */
    record Settings() implements ConversationState {
    }

    /**
     * The close-list screen of a list (TK-254) — navigation only. The all-done confirmation, the
     * unfinished-items options screen and the drop confirmation all share this state; the chosen path
     * advances via inline buttons carrying the list id.
     */
    record ClosingList(UUID listId) implements ConversationState {
    }

    /**
     * The "move unfinished items to another list" picker reached from the close-list options (TK-254) —
     * navigation only. Carries the id of the list being closed; the picked target id travels in the
     * button's {@code callback_data}.
     */
    record ClosingListTarget(UUID listId) implements ConversationState {
    }

    /**
     * The tags screen of a list (TK-259) — navigation only (remove a tag, quick-add a suggestion, or
     * enter the add-tag prompt). Reached from the per-list settings screen.
     */
    record EditingListTags(UUID listId) implements ConversationState {
    }

    /**
     * Typing a custom tag to add to a list (TK-259) — consumes plain-text input. Carries the id of the
     * tags-screen message so the submitted tag (or a validation notice) re-renders it in place, mirroring
     * {@link InvitingMember}.
     */
    record AddingTag(UUID listId, int messageId) implements ConversationState {
        @Override
        public boolean expectsTextInput() {
            return true;
        }
    }

    /**
     * Step 3 of the new-list flow (TK-258) — typing optional tags for the not-yet-created list, consuming
     * plain-text input. Carries the chosen {@code type} and validated {@code name} so the list is created
     * only once the tags are settled (a cancel here leaves no orphan list), plus the id of the prompt
     * message so the submitted tags (or a validation notice) re-render it in place.
     */
    record CreatingListTags(ListType type, String name, int promptMessageId) implements ConversationState {
        @Override
        public boolean expectsTextInput() {
            return true;
        }
    }

    /**
     * The "add matching pending items?" screen shown right after a tagged list is created (TK-258) —
     * navigation only (checkbox toggles plus add/drop/skip). Carries the new list's id and the currently
     * selected pending-item ids; the candidate items themselves are re-derived each render from the list's
     * tags, so {@code selectedIds} only needs to remember the selection (by id) across re-renders.
     */
    record CreatingListPendingCheck(UUID newListId, List<UUID> selectedIds) implements ConversationState {
    }
}
