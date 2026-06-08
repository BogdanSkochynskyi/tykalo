package io.tykalo.nudger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.type;

import io.tykalo.AbstractIntegrationTest;
import io.tykalo.user.User;
import io.tykalo.user.UserRepository;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Exercises {@link NudgerService} against the real Flyway-migrated schema: the username lookup (with
 * its case-insensitivity), per-(owner, invitee) deduplication, the PENDING pairing both entry points
 * create, and the consent transition (accept/decline, idempotent). Uses the 990_00x tg_chat_id range
 * (the singleton Postgres is shared and never reset between integration-test classes).
 */
class NudgerServiceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private NudgerService nudgerService;

    @Autowired
    private NudgerRepository nudgerRepository;

    @Autowired
    private NudgeLogRepository nudgeLogRepository;

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

    @Test
    void consent_accept_flipsPendingToActive_andCarriesOwner() {
        // Arrange
        final User owner = savedUser(990_015L, "owner9");
        savedUser(990_016L, "helper9");
        final UUID nudgerId = ((InviteResult.Invited) nudgerService.invite(owner, "helper9")).nudger().getId();

        // Act
        final ConsentResult result = nudgerService.consent(nudgerId, true);

        // Assert
        assertThat(result).asInstanceOf(type(ConsentResult.Accepted.class)).satisfies(accepted -> {
            assertThat(accepted.nudger().getStatus()).isEqualTo(NudgerStatus.ACTIVE);
            assertThat(accepted.owner().getId()).isEqualTo(owner.getId());
        });
        assertThat(nudgerRepository.findById(nudgerId)).get()
                .extracting(Nudger::getStatus).isEqualTo(NudgerStatus.ACTIVE);
    }

    @Test
    void consent_decline_flipsPendingToRejected() {
        // Arrange
        final User owner = savedUser(990_017L, "owner10");
        savedUser(990_018L, "helper10");
        final UUID nudgerId = ((InviteResult.Invited) nudgerService.invite(owner, "helper10")).nudger().getId();

        // Act
        final ConsentResult result = nudgerService.consent(nudgerId, false);

        // Assert
        assertThat(result).asInstanceOf(type(ConsentResult.Declined.class))
                .satisfies(declined -> assertThat(declined.nudger().getStatus()).isEqualTo(NudgerStatus.REJECTED));
        assertThat(nudgerRepository.findById(nudgerId)).get()
                .extracting(Nudger::getStatus).isEqualTo(NudgerStatus.REJECTED);
    }

    @Test
    void consent_isIdempotent_onReplayedTap() {
        // Arrange
        final User owner = savedUser(990_019L, "owner11");
        savedUser(990_020L, "helper11");
        final UUID nudgerId = ((InviteResult.Invited) nudgerService.invite(owner, "helper11")).nudger().getId();
        nudgerService.consent(nudgerId, true);

        // Act — a second tap (even the opposite choice) does not re-transition
        final ConsentResult replay = nudgerService.consent(nudgerId, false);

        // Assert
        assertThat(replay).asInstanceOf(type(ConsentResult.AlreadyDecided.class))
                .satisfies(already -> assertThat(already.nudger().getStatus()).isEqualTo(NudgerStatus.ACTIVE));
        assertThat(nudgerRepository.findById(nudgerId)).get()
                .extracting(Nudger::getStatus).isEqualTo(NudgerStatus.ACTIVE);
    }

    @Test
    void consent_returnsNotFound_forUnknownNudger() {
        assertThat(nudgerService.consent(UUID.randomUUID(), true))
                .isInstanceOf(ConsentResult.NotFound.class);
    }

    @Test
    void listActive_returnsActiveNudgers_orderedByKarmaDescending() {
        // Arrange — one ACTIVE pairing with high karma, one with low, plus a PENDING that must be excluded
        final User owner = savedUser(990_021L, "owner12");
        savedUser(990_022L, "lowkarma");
        savedUser(990_023L, "highkarma");
        savedUser(990_024L, "pendingone");
        activate(owner, "lowkarma", 1);
        activate(owner, "highkarma", 9);
        nudgerService.invite(owner, "pendingone");

        // Act
        final var summaries = nudgerService.listActive(owner);

        // Assert
        assertThat(summaries).extracting(NudgerSummary::username).containsExactly("highkarma", "lowkarma");
        assertThat(summaries).extracting(NudgerSummary::karmaScore).containsExactly(9, 1);
    }

    @Test
    void pause_movesActiveToPaused_andResumeMovesItBack() {
        // Arrange
        final User owner = savedUser(990_025L, "owner13");
        savedUser(990_026L, "helper13");
        activate(owner, "helper13", 0);

        // Act + Assert — pause
        assertThat(nudgerService.pause(owner, "@helper13")).asInstanceOf(type(NudgerActionResult.Ok.class))
                .satisfies(ok -> assertThat(ok.nudger().getStatus()).isEqualTo(NudgerStatus.PAUSED));

        // Act + Assert — resume
        assertThat(nudgerService.resume(owner, "helper13")).asInstanceOf(type(NudgerActionResult.Ok.class))
                .satisfies(ok -> assertThat(ok.nudger().getStatus()).isEqualTo(NudgerStatus.ACTIVE));
    }

    @Test
    void pause_isUnchanged_whenNotActive() {
        final User owner = savedUser(990_027L, "owner14");
        savedUser(990_028L, "helper14");
        nudgerService.invite(owner, "helper14"); // stays PENDING

        assertThat(nudgerService.pause(owner, "helper14")).asInstanceOf(type(NudgerActionResult.Unchanged.class))
                .satisfies(u -> assertThat(u.nudger().getStatus()).isEqualTo(NudgerStatus.PENDING));
    }

    @Test
    void resume_isUnchanged_whenAlreadyActive() {
        final User owner = savedUser(990_029L, "owner15");
        savedUser(990_030L, "helper15");
        activate(owner, "helper15", 0);

        assertThat(nudgerService.resume(owner, "helper15")).isInstanceOf(NudgerActionResult.Unchanged.class);
    }

    @Test
    void remove_deletesPairing_andCascadesNudgeLog() {
        // Arrange — an active pairing with a logged escalation referencing it
        final User owner = savedUser(990_031L, "owner16");
        savedUser(990_032L, "helper16");
        final Nudger nudger = activate(owner, "helper16", 0);
        final UUID nudgerId = Objects.requireNonNull(nudger.getId());
        final UUID targetId = UUID.randomUUID();
        nudgeLogRepository.save(NudgeLog.of(
                EscalationTargetType.TASK, targetId, nudgerId, 1, Instant.now(), "ping"));
        assertThat(nudgeLogRepository.findByTargetTypeAndTargetId(EscalationTargetType.TASK, targetId)).isNotEmpty();

        // Act
        final NudgerActionResult result = nudgerService.remove(owner, "@helper16");

        // Assert — pairing gone, and the FK cascade took its log rows with it
        assertThat(result).isInstanceOf(NudgerActionResult.Ok.class);
        assertThat(nudgerRepository.findById(nudgerId)).isEmpty();
        assertThat(nudgeLogRepository.findByTargetTypeAndTargetId(EscalationTargetType.TASK, targetId)).isEmpty();
    }

    @Test
    void manage_returnsNotRegistered_forUnknownUsername() {
        final User owner = savedUser(990_033L, "owner17");

        assertThat(nudgerService.pause(owner, "@ghost17")).asInstanceOf(type(NudgerActionResult.NotRegistered.class))
                .satisfies(nr -> assertThat(nr.username()).isEqualTo("ghost17"));
    }

    @Test
    void manage_returnsNotANudger_whenUserExistsButIsNotAPairing() {
        final User owner = savedUser(990_034L, "owner18");
        savedUser(990_035L, "stranger18");

        assertThat(nudgerService.remove(owner, "stranger18")).asInstanceOf(type(NudgerActionResult.NotANudger.class))
                .satisfies(n -> assertThat(n.username()).isEqualTo("stranger18"));
    }

    /** Invites {@code username}, consents on their behalf, and stamps {@code karma}, yielding an ACTIVE pairing. */
    private Nudger activate(final User owner, final String username, final int karma) {
        final UUID nudgerId = ((InviteResult.Invited) nudgerService.invite(owner, username)).nudger().getId();
        nudgerService.consent(Objects.requireNonNull(nudgerId), true);
        final Nudger nudger = nudgerRepository.findById(nudgerId).orElseThrow();
        nudger.setKarmaScore(karma);
        return nudgerRepository.save(nudger);
    }
}
