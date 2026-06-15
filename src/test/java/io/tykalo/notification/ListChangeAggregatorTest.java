package io.tykalo.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.tykalo.list.ListActivityEvent;
import io.tykalo.list.ListMember;
import io.tykalo.list.ListMemberRole;
import io.tykalo.list.ListMemberService;
import io.tykalo.list.ListService;
import io.tykalo.list.TaskList;
import io.tykalo.telegram.TelegramMessageGateway;
import io.tykalo.user.ListChangeNotificationPreference;
import io.tykalo.user.QuietHoursService;
import io.tykalo.user.User;
import io.tykalo.user.UserRepository;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ListChangeAggregatorTest {

    private static final Instant NOON = Instant.parse("2026-06-15T12:00:00Z");

    @Mock
    private ListService listService;
    @Mock
    private ListMemberService listMemberService;
    @Mock
    private UserRepository userRepository;
    @Mock
    private TelegramMessageGateway gateway;

    private final QuietHoursService quietHoursService = new QuietHoursService();
    private final InMemoryNotificationBuffer buffer = new InMemoryNotificationBuffer();
    private final NotificationMessageFormatter formatter = new NotificationMessageFormatter();

    private final Map<UUID, User> universe = new HashMap<>();

    private ListChangeAggregator aggregator;

    private final UUID actorId = UUID.randomUUID();
    private final UUID listId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        aggregator = new ListChangeAggregator(buffer, formatter, listService, listMemberService,
                userRepository, quietHoursService, gateway);
        lenient().when(userRepository.findAllById(any())).thenAnswer(inv -> resolveAll(inv.getArgument(0)));
        lenient().when(userRepository.findById(any()))
                .thenAnswer(inv -> Optional.ofNullable(universe.get(inv.getArgument(0))));
    }

    private List<User> resolveAll(final Iterable<UUID> ids) {
        final List<User> out = new ArrayList<>();
        ids.forEach(id -> {
            if (universe.containsKey(id)) {
                out.add(universe.get(id));
            }
        });
        return out;
    }

    private User user(final String username, final ListChangeNotificationPreference pref) {
        final User user = User.create((long) (universe.size() + 1), username, ZoneId.of("UTC"), "uk");
        user.setId(UUID.randomUUID());
        user.setListChangeNotifications(pref);
        user.setQuietHoursStart(null);
        user.setQuietHoursEnd(null);
        universe.put(user.getId(), user);
        return user;
    }

    private TaskList sharedList(final UUID ownerId) {
        final TaskList list = new TaskList();
        list.setId(listId);
        list.setOwnerId(ownerId);
        list.setName("Groceries");
        return list;
    }

    private void members(final ListMember... members) {
        when(listMemberService.activeMembers(listId)).thenReturn(List.of(members));
    }

    private ListActivityEvent added(final int count) {
        return new ListActivityEvent(listId, actorId, ListActivityEvent.Kind.ADDED, count);
    }

    @Test
    void record_buffersChange_forOtherActiveMembers() {
        final User owner = user("anna", ListChangeNotificationPreference.BATCHED);
        final User member = user("petro", ListChangeNotificationPreference.BATCHED);
        when(listService.getActiveById(listId)).thenReturn(Optional.of(sharedList(owner.getId())));
        members(ListMember.of(listId, owner.getId(), ListMemberRole.OWNER),
                ListMember.of(listId, member.getId(), ListMemberRole.MEMBER));
        // The actor is one of the members.
        final ListActivityEvent event = new ListActivityEvent(listId, member.getId(),
                ListActivityEvent.Kind.ADDED, 2);

        aggregator.record(event, NOON);

        assertThat(buffer.hasWindow(owner.getId(), listId, ListChangeNotificationPreference.BATCHED)).isTrue();
        assertThat(buffer.hasWindow(member.getId(), listId, ListChangeNotificationPreference.BATCHED)).isFalse();
    }

    @Test
    void record_skipsActor_neverNotifiesSelf() {
        final User actor = user("anna", ListChangeNotificationPreference.INSTANT);
        when(listService.getActiveById(listId)).thenReturn(Optional.of(sharedList(actor.getId())));
        members(ListMember.of(listId, actor.getId(), ListMemberRole.OWNER));
        final ListActivityEvent event = new ListActivityEvent(listId, actor.getId(),
                ListActivityEvent.Kind.COMPLETED, 1);

        aggregator.record(event, NOON);

        assertThat(buffer.dueWindows(NOON.plus(1, ChronoUnit.HOURS))).isEmpty();
    }

    @Test
    void record_off_buffersNothing() {
        final User owner = user("anna", ListChangeNotificationPreference.OFF);
        when(listService.getActiveById(listId)).thenReturn(Optional.of(sharedList(owner.getId())));
        members(ListMember.of(listId, owner.getId(), ListMemberRole.OWNER));

        aggregator.record(added(3), NOON);

        assertThat(buffer.hasWindow(owner.getId(), listId, ListChangeNotificationPreference.OFF)).isFalse();
        assertThat(buffer.dailyFor(owner.getId())).isEmpty();
    }

    @Test
    void record_dailyDigest_goesToDailyBuffer_notWindow() {
        final User owner = user("anna", ListChangeNotificationPreference.DAILY_DIGEST);
        when(listService.getActiveById(listId)).thenReturn(Optional.of(sharedList(owner.getId())));
        members(ListMember.of(listId, owner.getId(), ListMemberRole.OWNER));

        aggregator.record(added(4), NOON);

        assertThat(buffer.dailyFor(owner.getId())).hasSize(1);
        assertThat(buffer.hasWindow(owner.getId(), listId, ListChangeNotificationPreference.DAILY_DIGEST)).isFalse();
    }

    @Test
    void record_includesSynthesizedOwner_whenNoOwnerRow() {
        // A menu-created list later shared: only a MEMBER row exists; the owner is synthesized from owner_id.
        final User owner = user("anna", ListChangeNotificationPreference.BATCHED);
        final User member = user("petro", ListChangeNotificationPreference.BATCHED);
        when(listService.getActiveById(listId)).thenReturn(Optional.of(sharedList(owner.getId())));
        members(ListMember.of(listId, member.getId(), ListMemberRole.MEMBER));
        final ListActivityEvent event = new ListActivityEvent(listId, member.getId(),
                ListActivityEvent.Kind.ADDED, 1);

        aggregator.record(event, NOON);

        assertThat(buffer.hasWindow(owner.getId(), listId, ListChangeNotificationPreference.BATCHED)).isTrue();
    }

    @Test
    void record_singleUserList_producesNoRecipients() {
        final User owner = user("anna", ListChangeNotificationPreference.BATCHED);
        when(listService.getActiveById(listId)).thenReturn(Optional.of(sharedList(owner.getId())));
        when(listMemberService.activeMembers(listId)).thenReturn(List.of());
        // The actor IS the synthesized owner.
        final ListActivityEvent event = new ListActivityEvent(listId, owner.getId(),
                ListActivityEvent.Kind.ADDED, 1);

        aggregator.record(event, NOON);

        assertThat(buffer.dueWindows(NOON.plus(1, ChronoUnit.HOURS))).isEmpty();
    }

    @Test
    void flushDueWindows_sendsBatchedMessage_inExpectedFormat() {
        final User anna = user("anna", ListChangeNotificationPreference.BATCHED);
        final User petro = user("petro", ListChangeNotificationPreference.BATCHED);
        when(listService.getActiveById(listId)).thenReturn(Optional.of(sharedList(petro.getId())));
        // anna receives; she added 4, petro (also a recipient elsewhere) completed 2 — both done by others.
        buffer.addToWindow(anna.getId(), listId, ListChangeNotificationPreference.BATCHED,
                petro.getId(), ListActivityEvent.Kind.ADDED, 4, NOON);
        buffer.addToWindow(anna.getId(), listId, ListChangeNotificationPreference.BATCHED,
                actorUser("kira").getId(), ListActivityEvent.Kind.COMPLETED, 2, NOON);

        aggregator.flushDueWindows(NOON.plus(1, ChronoUnit.SECONDS));

        final ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(gateway).sendMarkdown(eq(anna.getTgChatId()), text.capture(), isNull());
        assertThat(text.getValue()).contains("Changes in").contains("Groceries")
                .contains("@petro added 4 items").contains("@kira completed 2 items");
        assertThat(buffer.hasWindow(anna.getId(), listId, ListChangeNotificationPreference.BATCHED)).isFalse();
    }

    @Test
    void flushDueWindows_defersDuringQuietHours_keepsBucket() {
        final User anna = user("anna", ListChangeNotificationPreference.BATCHED);
        anna.setQuietHoursStart(java.time.LocalTime.of(22, 0));
        anna.setQuietHoursEnd(java.time.LocalTime.of(7, 0));
        final Instant night = Instant.parse("2026-06-15T23:00:00Z");
        buffer.addToWindow(anna.getId(), listId, ListChangeNotificationPreference.BATCHED,
                actorId, ListActivityEvent.Kind.ADDED, 1, night);

        aggregator.flushDueWindows(night.plus(1, ChronoUnit.SECONDS));

        verify(gateway, never()).sendMarkdown(anyLong(), any(), any());
        assertThat(buffer.hasWindow(anna.getId(), listId, ListChangeNotificationPreference.BATCHED)).isTrue();
        // Re-flushing once quiet hours end (07:00) delivers it.
        when(listService.getActiveById(listId)).thenReturn(Optional.of(sharedList(actorId)));
        aggregator.flushDueWindows(Instant.parse("2026-06-16T07:00:00Z"));
        verify(gateway).sendMarkdown(eq(anna.getTgChatId()), any(), isNull());
    }

    @Test
    void flushDueWindows_dropsBucket_whenListGone() {
        final User anna = user("anna", ListChangeNotificationPreference.INSTANT);
        when(listService.getActiveById(listId)).thenReturn(Optional.empty());
        buffer.addToWindow(anna.getId(), listId, ListChangeNotificationPreference.INSTANT,
                actorId, ListActivityEvent.Kind.ADDED, 1, NOON);

        aggregator.flushDueWindows(NOON.plus(1, ChronoUnit.SECONDS));

        verify(gateway, never()).sendMarkdown(anyLong(), any(), any());
        assertThat(buffer.hasWindow(anna.getId(), listId, ListChangeNotificationPreference.INSTANT)).isFalse();
    }

    @Test
    void flushDailyDigests_sendsAtMorningHour_andClears() {
        final User anna = user("anna", ListChangeNotificationPreference.DAILY_DIGEST);
        anna.setDigestHour(8);
        when(userRepository.findByListChangeNotifications(ListChangeNotificationPreference.DAILY_DIGEST))
                .thenReturn(List.of(anna));
        when(listService.getActiveById(listId)).thenReturn(Optional.of(sharedList(actorId)));
        buffer.addToDaily(anna.getId(), listId, actorId, ListActivityEvent.Kind.ADDED, 5);

        aggregator.flushDailyDigests(Instant.parse("2026-06-15T08:30:00Z"));

        final ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(gateway).sendMarkdown(eq(anna.getTgChatId()), text.capture(), isNull());
        assertThat(text.getValue()).contains("Daily list summary").contains("Groceries").contains("added 5 items");
        assertThat(buffer.dailyFor(anna.getId())).isEmpty();
    }

    @Test
    void flushDailyDigests_skips_whenNotMorningHour() {
        final User anna = user("anna", ListChangeNotificationPreference.DAILY_DIGEST);
        anna.setDigestHour(8);
        when(userRepository.findByListChangeNotifications(ListChangeNotificationPreference.DAILY_DIGEST))
                .thenReturn(List.of(anna));
        buffer.addToDaily(anna.getId(), listId, actorId, ListActivityEvent.Kind.ADDED, 5);

        aggregator.flushDailyDigests(Instant.parse("2026-06-15T15:00:00Z"));

        verify(gateway, never()).sendMarkdown(anyLong(), any(), any());
        assertThat(buffer.dailyFor(anna.getId())).hasSize(1);
    }

    @Test
    void flushDailyDigests_skips_duringQuietHours() {
        final User anna = user("anna", ListChangeNotificationPreference.DAILY_DIGEST);
        anna.setDigestHour(3);
        anna.setQuietHoursStart(java.time.LocalTime.of(22, 0));
        anna.setQuietHoursEnd(java.time.LocalTime.of(7, 0));
        when(userRepository.findByListChangeNotifications(ListChangeNotificationPreference.DAILY_DIGEST))
                .thenReturn(List.of(anna));
        buffer.addToDaily(anna.getId(), listId, actorId, ListActivityEvent.Kind.ADDED, 5);

        aggregator.flushDailyDigests(Instant.parse("2026-06-15T03:00:00Z"));

        verify(gateway, never()).sendMarkdown(anyLong(), any(), any());
        assertThat(buffer.dailyFor(anna.getId())).hasSize(1);
    }

    private User actorUser(final String username) {
        return user(username, ListChangeNotificationPreference.OFF);
    }
}
