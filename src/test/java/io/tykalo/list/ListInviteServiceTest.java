package io.tykalo.list;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.tykalo.telegram.TelegramBotProperties;
import io.tykalo.user.User;
import io.tykalo.user.UserRepository;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class ListInviteServiceTest {

    @Mock
    private ListMemberRepository listMemberRepository;
    @Mock
    private ListRepository listRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private ListPermissionService permissionService;
    @Mock
    private StringRedisTemplate redis;
    @Mock
    private TelegramBotProperties botProperties;

    @InjectMocks
    private ListInviteService service;

    // --- inviteByUsername ---------------------------------------------------

    @Test
    void inviteByUsername_createsPendingMembership_forRegisteredUser() {
        // Arrange
        final User actor = user(1L, "alice");
        final User invitee = user(2L, "bob");
        final TaskList list = list(actor, "Groceries");
        when(listRepository.findById(list.getId())).thenReturn(Optional.of(list));
        when(userRepository.findByTgUsernameIgnoreCase("bob")).thenReturn(Optional.of(invitee));
        when(listMemberRepository.findByListIdAndUserId(list.getId(), invitee.getId()))
                .thenReturn(Optional.empty());
        when(listMemberRepository.save(any())).thenAnswer(saveWithId());

        // Act
        final ListInviteResult result = service.inviteByUsername(actor, list.getId(), ListMemberRole.MEMBER, "@bob");

        // Assert
        assertThat(result).isInstanceOfSatisfying(ListInviteResult.Invited.class, invited -> {
            assertThat(invited.member().getStatus()).isEqualTo(ListMemberStatus.PENDING);
            assertThat(invited.member().getRole()).isEqualTo(ListMemberRole.MEMBER);
            assertThat(invited.member().getInvitedBy()).isEqualTo(actor.getId());
            assertThat(invited.invitee()).isEqualTo(invitee);
            assertThat(invited.list()).isEqualTo(list);
        });
    }

    @Test
    void inviteByUsername_returnsNotRegistered_forUnknownUsername() {
        final User actor = user(1L, "alice");
        final TaskList list = list(actor, "Groceries");
        when(listRepository.findById(list.getId())).thenReturn(Optional.of(list));
        when(userRepository.findByTgUsernameIgnoreCase("ghost")).thenReturn(Optional.empty());

        final ListInviteResult result = service.inviteByUsername(actor, list.getId(), ListMemberRole.MEMBER, "@ghost");

        assertThat(result).isEqualTo(new ListInviteResult.NotRegistered("ghost"));
        verify(listMemberRepository, never()).save(any());
    }

    @Test
    void inviteByUsername_returnsSelfInvite_whenInvitingYourself() {
        final User actor = user(1L, "alice");
        final TaskList list = list(actor, "Groceries");
        when(listRepository.findById(list.getId())).thenReturn(Optional.of(list));
        when(userRepository.findByTgUsernameIgnoreCase("alice")).thenReturn(Optional.of(actor));

        final ListInviteResult result = service.inviteByUsername(actor, list.getId(), ListMemberRole.MEMBER, "alice");

        assertThat(result).isInstanceOf(ListInviteResult.SelfInvite.class);
    }

    @Test
    void inviteByUsername_returnsAlreadyMember_whenActiveMembershipExists() {
        final User actor = user(1L, "alice");
        final User invitee = user(2L, "bob");
        final TaskList list = list(actor, "Groceries");
        final ListMember active = ListMember.of(list.getId(), invitee.getId(), ListMemberRole.MEMBER);
        when(listRepository.findById(list.getId())).thenReturn(Optional.of(list));
        when(userRepository.findByTgUsernameIgnoreCase("bob")).thenReturn(Optional.of(invitee));
        when(listMemberRepository.findByListIdAndUserId(list.getId(), invitee.getId()))
                .thenReturn(Optional.of(active));

        final ListInviteResult result = service.inviteByUsername(actor, list.getId(), ListMemberRole.MEMBER, "bob");

        assertThat(result).isEqualTo(new ListInviteResult.AlreadyMember(invitee));
        verify(listMemberRepository, never()).save(any());
    }

    @Test
    void inviteByUsername_returnsAlreadyPending_whenPendingMembershipExists() {
        final User actor = user(1L, "alice");
        final User invitee = user(2L, "bob");
        final TaskList list = list(actor, "Groceries");
        final ListMember pending = ListMember.pendingInvite(
                list.getId(), invitee.getId(), ListMemberRole.MEMBER, actor.getId());
        when(listRepository.findById(list.getId())).thenReturn(Optional.of(list));
        when(userRepository.findByTgUsernameIgnoreCase("bob")).thenReturn(Optional.of(invitee));
        when(listMemberRepository.findByListIdAndUserId(list.getId(), invitee.getId()))
                .thenReturn(Optional.of(pending));

        final ListInviteResult result = service.inviteByUsername(actor, list.getId(), ListMemberRole.MEMBER, "bob");

        assertThat(result).isInstanceOf(ListInviteResult.AlreadyPending.class);
        verify(listMemberRepository, never()).save(any());
    }

    @Test
    void inviteByUsername_propagatesPermissionDenied() {
        final User actor = user(1L, "alice");
        final UUID listId = UUID.randomUUID();
        doThrow(new ListPermissionDeniedException(actor.getId(), listId, "manage members", ListMemberRole.MEMBER))
                .when(permissionService).requireCanManageMembers(actor.getId(), listId);

        assertThatThrownBy(() -> service.inviteByUsername(actor, listId, ListMemberRole.MEMBER, "bob"))
                .isInstanceOf(ListPermissionDeniedException.class);
        verify(listMemberRepository, never()).save(any());
    }

    // --- createShareLink ----------------------------------------------------

    @Test
    void createShareLink_buildsDeepLink_andStoresRedisToken() {
        final User actor = user(1L, "alice");
        final UUID listId = UUID.randomUUID();
        final ValueOperations<String, String> valueOps = mockValueOps();
        when(botProperties.getUsername()).thenReturn("TykaloBot");

        final String link = service.createShareLink(actor, listId, ListMemberRole.MEMBER);

        assertThat(link).startsWith("https://t.me/TykaloBot?start=" + ListInvite.PAYLOAD_PREFIX);
        verify(valueOps).set(startsWith("list_invite:"), eq("1"), eq(ListInviteService.SHARE_LINK_TTL));
    }

    @Test
    void createShareLink_propagatesPermissionDenied() {
        final User actor = user(1L, "alice");
        final UUID listId = UUID.randomUUID();
        doThrow(new ListPermissionDeniedException(actor.getId(), listId, "manage members", null))
                .when(permissionService).requireCanManageMembers(actor.getId(), listId);

        assertThatThrownBy(() -> service.createShareLink(actor, listId, ListMemberRole.MEMBER))
                .isInstanceOf(ListPermissionDeniedException.class);
    }

    // --- acceptViaDeepLink --------------------------------------------------

    @Test
    void acceptViaDeepLink_createsPendingMembership_whenTokenValid() {
        final User inviter = user(1L, "alice");
        final User clicker = user(2L, "bob");
        final TaskList list = list(inviter, "Groceries");
        final ListInvite.Payload payload = new ListInvite.Payload(list.getId(), inviter.getId(), ListMemberRole.MEMBER);
        when(redis.hasKey(anyString())).thenReturn(true);
        when(listRepository.findById(list.getId())).thenReturn(Optional.of(list));
        when(userRepository.findById(inviter.getId())).thenReturn(Optional.of(inviter));
        when(listMemberRepository.findByListIdAndUserId(list.getId(), clicker.getId()))
                .thenReturn(Optional.empty());
        when(listMemberRepository.save(any())).thenAnswer(saveWithId());

        final DeepLinkInviteResult result = service.acceptViaDeepLink(clicker, payload);

        assertThat(result).isInstanceOfSatisfying(DeepLinkInviteResult.Invited.class, invited -> {
            assertThat(invited.member().getStatus()).isEqualTo(ListMemberStatus.PENDING);
            assertThat(invited.member().getUserId()).isEqualTo(clicker.getId());
            assertThat(invited.inviter()).isEqualTo(inviter);
            assertThat(invited.list()).isEqualTo(list);
        });
    }

    @Test
    void acceptViaDeepLink_returnsExpired_whenRedisTokenGone() {
        final User clicker = user(2L, "bob");
        final ListInvite.Payload payload =
                new ListInvite.Payload(UUID.randomUUID(), UUID.randomUUID(), ListMemberRole.MEMBER);
        when(redis.hasKey(anyString())).thenReturn(false);

        final DeepLinkInviteResult result = service.acceptViaDeepLink(clicker, payload);

        assertThat(result).isInstanceOf(DeepLinkInviteResult.Expired.class);
        verify(listMemberRepository, never()).save(any());
    }

    @Test
    void acceptViaDeepLink_returnsSelfInvite_whenClickerIssuedTheLink() {
        final User actor = user(1L, "alice");
        final ListInvite.Payload payload =
                new ListInvite.Payload(UUID.randomUUID(), actor.getId(), ListMemberRole.MEMBER);
        when(redis.hasKey(anyString())).thenReturn(true);

        final DeepLinkInviteResult result = service.acceptViaDeepLink(actor, payload);

        assertThat(result).isInstanceOf(DeepLinkInviteResult.SelfInvite.class);
    }

    @Test
    void acceptViaDeepLink_returnsUnavailable_whenListGone() {
        final User clicker = user(2L, "bob");
        final UUID listId = UUID.randomUUID();
        final ListInvite.Payload payload = new ListInvite.Payload(listId, UUID.randomUUID(), ListMemberRole.MEMBER);
        when(redis.hasKey(anyString())).thenReturn(true);
        when(listRepository.findById(listId)).thenReturn(Optional.empty());
        when(userRepository.findById(payload.invitedBy())).thenReturn(Optional.of(user(1L, "alice")));

        final DeepLinkInviteResult result = service.acceptViaDeepLink(clicker, payload);

        assertThat(result).isInstanceOf(DeepLinkInviteResult.Unavailable.class);
    }

    @Test
    void acceptViaDeepLink_returnsAlreadyMember_whenActiveMembershipExists() {
        final User inviter = user(1L, "alice");
        final User clicker = user(2L, "bob");
        final TaskList list = list(inviter, "Groceries");
        final ListInvite.Payload payload = new ListInvite.Payload(list.getId(), inviter.getId(), ListMemberRole.MEMBER);
        when(redis.hasKey(anyString())).thenReturn(true);
        when(listRepository.findById(list.getId())).thenReturn(Optional.of(list));
        when(userRepository.findById(inviter.getId())).thenReturn(Optional.of(inviter));
        when(listMemberRepository.findByListIdAndUserId(list.getId(), clicker.getId()))
                .thenReturn(Optional.of(ListMember.of(list.getId(), clicker.getId(), ListMemberRole.MEMBER)));

        final DeepLinkInviteResult result = service.acceptViaDeepLink(clicker, payload);

        assertThat(result).isInstanceOf(DeepLinkInviteResult.AlreadyMember.class);
        verify(listMemberRepository, never()).save(any());
    }

    // --- respond ------------------------------------------------------------

    @Test
    void respond_accept_flipsToActive_andResolvesInviter() {
        final User inviter = user(1L, "alice");
        final User invitee = user(2L, "bob");
        final TaskList list = list(inviter, "Groceries");
        final ListMember member = pendingMember(list.getId(), invitee.getId(), inviter.getId());
        when(listMemberRepository.findById(member.getId())).thenReturn(Optional.of(member));
        when(listRepository.findById(list.getId())).thenReturn(Optional.of(list));
        when(userRepository.findById(inviter.getId())).thenReturn(Optional.of(inviter));

        final InviteResponseResult result = service.respond(member.getId(), true);

        assertThat(result).isInstanceOfSatisfying(InviteResponseResult.Accepted.class, accepted -> {
            assertThat(accepted.member().getStatus()).isEqualTo(ListMemberStatus.ACTIVE);
            assertThat(accepted.list()).isEqualTo(list);
            assertThat(accepted.inviterOpt()).contains(inviter);
        });
        verify(listMemberRepository).save(member);
    }

    @Test
    void respond_decline_deletesPendingRow() {
        final User inviter = user(1L, "alice");
        final User invitee = user(2L, "bob");
        final TaskList list = list(inviter, "Groceries");
        final ListMember member = pendingMember(list.getId(), invitee.getId(), inviter.getId());
        when(listMemberRepository.findById(member.getId())).thenReturn(Optional.of(member));
        when(listRepository.findById(list.getId())).thenReturn(Optional.of(list));
        when(userRepository.findById(inviter.getId())).thenReturn(Optional.of(inviter));

        final InviteResponseResult result = service.respond(member.getId(), false);

        assertThat(result).isInstanceOf(InviteResponseResult.Declined.class);
        verify(listMemberRepository).delete(member);
        verify(listMemberRepository, never()).save(any());
    }

    @Test
    void respond_returnsAlreadyActive_onReplayedAccept() {
        final ListMember active = ListMember.of(UUID.randomUUID(), UUID.randomUUID(), ListMemberRole.MEMBER);
        active.setId(UUID.randomUUID());
        when(listMemberRepository.findById(active.getId())).thenReturn(Optional.of(active));

        final InviteResponseResult result = service.respond(active.getId(), true);

        assertThat(result).isInstanceOf(InviteResponseResult.AlreadyActive.class);
        verify(listMemberRepository, never()).save(any());
        verify(listMemberRepository, never()).delete(any());
    }

    @Test
    void respond_returnsNotFound_whenMembershipGone() {
        final UUID memberId = UUID.randomUUID();
        when(listMemberRepository.findById(memberId)).thenReturn(Optional.empty());

        final InviteResponseResult result = service.respond(memberId, false);

        assertThat(result).isInstanceOf(InviteResponseResult.NotFound.class);
    }

    // --- helpers ------------------------------------------------------------

    private org.mockito.stubbing.Answer<ListMember> saveWithId() {
        return invocation -> {
            final ListMember member = invocation.getArgument(0);
            if (member.getId() == null) {
                member.setId(UUID.randomUUID());
            }
            return member;
        };
    }

    @SuppressWarnings("unchecked")
    private ValueOperations<String, String> mockValueOps() {
        final ValueOperations<String, String> valueOps = org.mockito.Mockito.mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        return valueOps;
    }

    private ListMember pendingMember(final UUID listId, final UUID userId, final UUID invitedBy) {
        final ListMember member = ListMember.pendingInvite(listId, userId, ListMemberRole.MEMBER, invitedBy);
        member.setId(UUID.randomUUID());
        return member;
    }

    private User user(final long chatId, final String username) {
        final User user = User.create(chatId, username, ZoneId.of("Europe/Kyiv"), "uk");
        user.setId(UUID.randomUUID());
        return user;
    }

    private TaskList list(final User owner, final String name) {
        final TaskList list = TaskList.of(owner, name, ListType.CHECKLIST);
        list.setId(UUID.randomUUID());
        return list;
    }
}
