package io.tykalo.telegram.conversation;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.tykalo.list.ListType;
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

    /** Naming a new list of the chosen {@code type} (TK-185) — consumes plain-text input. */
    record CreatingListName(ListType type) implements ConversationState {
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
}
