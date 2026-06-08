package io.tykalo.nudger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.type;

import io.tykalo.AbstractIntegrationTest;
import io.tykalo.user.User;
import io.tykalo.user.UserRepository;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Exercises {@link NudgerService} against the real Flyway-migrated schema: the username lookup (with
 * its case-insensitivity), per-(owner, invitee) deduplication and the PENDING pairing both entry
 * points create. Uses the 990_00x tg_chat_id range (the singleton Postgres is shared and never reset
 * between integration-test classes).
 */
class NudgerServiceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private NudgerService nudgerService;

    @Autowired
    private NudgerRepository nudgerRepository;

    @Autowired
    private UserRepository userRepository;

    private User savedUser(final long tgChatId, final String username) {
        return userRepository.save(User.create(tgChatId, username, ZoneId.of("Europe/Kyiv"), "uk"));
    }

    @Test
    void invite_createsPendingPairing_forRegisteredInvitee() {
        // Arrange
        final User owner = savedUser(990_001L, "owner1");
        final User invitee = savedUser(990_002L, "helper1");

        // Act
        final InviteResult result = nudgerService.invite(owner, "@helper1");

        // Assert
        assertThat(result).asInstanceOf(type(InviteResult.Invited.class)).satisfies(invited -> {
            assertThat(invited.invitee().getId()).isEqualTo(invitee.getId());
            assertThat(invited.nudger().getStatus()).isEqualTo(NudgerStatus.PENDING);
        });
        assertThat(nudgerRepository.findByOwnerIdAndNudgerUserId(owner.getId(), invitee.getId())).isPresent();
    }

    @Test
    void invite_isCaseInsensitive_andStripsLeadingAt() {
        // Arrange
        final User owner = savedUser(990_003L, "owner2");
        final User invitee = savedUser(990_004L, "Helper2");

        // Act — different case, with and without the @
        final InviteResult result = nudgerService.invite(owner, "@hELPer2");

        // Assert
        assertThat(result).asInstanceOf(type(InviteResult.Invited.class))
                .satisfies(invited -> assertThat(invited.invitee().getId()).isEqualTo(invitee.getId()));
    }

    @Test
    void invite_deduplicates_onSecondInvite() {
        // Arrange
        final User owner = savedUser(990_005L, "owner3");
        savedUser(990_006L, "helper3");
        final InviteResult first = nudgerService.invite(owner, "helper3");
        final UUID nudgerId = ((InviteResult.Invited) first).nudger().getId();

        // Act
        final InviteResult second = nudgerService.invite(owner, "helper3");

        // Assert — same pairing, no second row
        assertThat(second).asInstanceOf(type(InviteResult.AlreadyInvited.class))
                .satisfies(already -> assertThat(already.nudger().getId()).isEqualTo(nudgerId));
        assertThat(nudgerRepository.findByOwnerId(owner.getId())).hasSize(1);
    }

    @Test
    void invite_returnsNotRegistered_forUnknownUsername() {
        final User owner = savedUser(990_007L, "owner4");

        final InviteResult result = nudgerService.invite(owner, "@ghost");

        assertThat(result).asInstanceOf(type(InviteResult.NotRegistered.class))
                .satisfies(nr -> assertThat(nr.username()).isEqualTo("ghost"));
    }

    @Test
    void invite_rejectsSelfInvite() {
        final User owner = savedUser(990_008L, "owner5");

        assertThat(nudgerService.invite(owner, "@owner5")).isInstanceOf(InviteResult.SelfInvite.class);
    }

    @Test
    void acceptViaDeepLink_createsPendingPairing() {
        // Arrange
        final User owner = savedUser(990_009L, "owner6");
        final User invitee = savedUser(990_010L, "helper6");

        // Act
        final AcceptResult result = nudgerService.acceptViaDeepLink(invitee, owner.getId());

        // Assert
        assertThat(result).asInstanceOf(type(AcceptResult.Invited.class)).satisfies(invited -> {
            assertThat(invited.owner().getId()).isEqualTo(owner.getId());
            assertThat(invited.nudger().getStatus()).isEqualTo(NudgerStatus.PENDING);
        });
        assertThat(nudgerRepository.findByOwnerIdAndNudgerUserId(owner.getId(), invitee.getId())).isPresent();
    }

    @Test
    void acceptViaDeepLink_deduplicatesReusedLink() {
        final User owner = savedUser(990_011L, "owner7");
        final User invitee = savedUser(990_012L, "helper7");
        nudgerService.acceptViaDeepLink(invitee, owner.getId());

        final AcceptResult second = nudgerService.acceptViaDeepLink(invitee, owner.getId());

        assertThat(second).isInstanceOf(AcceptResult.AlreadyInvited.class);
        assertThat(nudgerRepository.findByOwnerId(owner.getId())).hasSize(1);
    }

    @Test
    void acceptViaDeepLink_returnsOwnerGone_forUnknownOwner() {
        final User invitee = savedUser(990_013L, "helper8");

        assertThat(nudgerService.acceptViaDeepLink(invitee, UUID.randomUUID()))
                .isInstanceOf(AcceptResult.OwnerGone.class);
    }

    @Test
    void acceptViaDeepLink_rejectsSelfInvite() {
        final User self = savedUser(990_014L, "loner");

        assertThat(nudgerService.acceptViaDeepLink(self, self.getId()))
                .isInstanceOf(AcceptResult.SelfInvite.class);
    }
}
