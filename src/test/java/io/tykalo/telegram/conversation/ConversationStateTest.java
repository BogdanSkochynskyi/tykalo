package io.tykalo.telegram.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tykalo.list.ListType;
import io.tykalo.menu.HelpTopic;
import io.tykalo.telegram.conversation.ConversationState.AddingItems;
import io.tykalo.telegram.conversation.ConversationState.ClosingList;
import io.tykalo.telegram.conversation.ConversationState.ClosingListTarget;
import io.tykalo.telegram.conversation.ConversationState.CreatingListName;
import io.tykalo.telegram.conversation.ConversationState.AddingTag;
import io.tykalo.telegram.conversation.ConversationState.CreatingListType;
import io.tykalo.telegram.conversation.ConversationState.EditingListTags;
import io.tykalo.telegram.conversation.ConversationState.Help;
import io.tykalo.telegram.conversation.ConversationState.HelpCategory;
import io.tykalo.telegram.conversation.ConversationState.Idle;
import io.tykalo.telegram.conversation.ConversationState.ListSettings;
import io.tykalo.telegram.conversation.ConversationState.ListView;
import io.tykalo.telegram.conversation.ConversationState.Lists;
import io.tykalo.telegram.conversation.ConversationState.MainMenu;
import io.tykalo.telegram.conversation.ConversationState.MembersScreen;
import io.tykalo.telegram.conversation.ConversationState.RenamingList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConversationStateTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void everyVariant_survivesAJsonRoundTrip() throws Exception {
        final UUID listId = UUID.randomUUID();
        final List<ConversationState> all = List.of(
                new Idle(),
                new MainMenu(),
                new Lists(),
                new ListView(listId),
                new AddingItems(listId, 42),
                new CreatingListType(),
                new CreatingListName(ListType.PROJECT, 7),
                new ListSettings(listId),
                new RenamingList(listId),
                new Help(),
                new HelpCategory(HelpTopic.NUDGERS),
                new MembersScreen(listId),
                new ClosingList(listId),
                new ClosingListTarget(listId),
                new EditingListTags(listId),
                new AddingTag(listId, 13));

        for (final ConversationState state : all) {
            final String json = mapper.writeValueAsString(state);
            final ConversationState restored = mapper.readValue(json, ConversationState.class);
            assertThat(restored).as("round-trip of %s via %s", state, json).isEqualTo(state);
        }
    }

    @Test
    void json_carriesTheStableDiscriminator_andContextFields() throws Exception {
        final UUID listId = UUID.fromString("11111111-2222-3333-4444-555555555555");

        assertThat(mapper.writeValueAsString(new Idle())).isEqualTo("{\"@type\":\"IDLE\"}");
        assertThat(mapper.writeValueAsString(new ListView(listId)))
                .isEqualTo("{\"@type\":\"LIST_VIEW\",\"listId\":\"" + listId + "\"}");
        // The "type" component must not collide with the "@type" discriminator.
        assertThat(mapper.writeValueAsString(new CreatingListName(ListType.CHECKLIST, 42)))
                .isEqualTo("{\"@type\":\"CREATING_LIST_NAME\",\"type\":\"CHECKLIST\",\"promptMessageId\":42}");
    }

    @Test
    void deserializes_byDiscriminator_intoTheConcreteType() throws Exception {
        final ConversationState restored =
                mapper.readValue("{\"@type\":\"ADDING_ITEMS\",\"listId\":\"" + UUID.randomUUID() + "\"}",
                        ConversationState.class);

        assertThat(restored).isInstanceOf(AddingItems.class);
    }

    @Test
    void expectsTextInput_isTrue_onlyForTheDataEntryStates() {
        final UUID listId = UUID.randomUUID();

        assertThat(new AddingItems(listId, 42).expectsTextInput()).isTrue();
        assertThat(new CreatingListName(ListType.INBOX, 1).expectsTextInput()).isTrue();
        assertThat(new RenamingList(listId).expectsTextInput()).isTrue();
        assertThat(new AddingTag(listId, 13).expectsTextInput()).isTrue();

        assertThat(new Idle().expectsTextInput()).isFalse();
        assertThat(new MainMenu().expectsTextInput()).isFalse();
        assertThat(new Lists().expectsTextInput()).isFalse();
        assertThat(new ListView(listId).expectsTextInput()).isFalse();
        assertThat(new CreatingListType().expectsTextInput()).isFalse();
        assertThat(new ListSettings(listId).expectsTextInput()).isFalse();
        assertThat(new Help().expectsTextInput()).isFalse();
        assertThat(new HelpCategory(HelpTopic.LISTS).expectsTextInput()).isFalse();
        assertThat(new MembersScreen(listId).expectsTextInput()).isFalse();
        assertThat(new ClosingList(listId).expectsTextInput()).isFalse();
        assertThat(new ClosingListTarget(listId).expectsTextInput()).isFalse();
        assertThat(new EditingListTags(listId).expectsTextInput()).isFalse();
    }
}
