package io.tykalo.nudger;

import io.tykalo.user.User;
import io.tykalo.user.UserRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates nudger pairings (TK-152). Both entry points leave the pairing {@link NudgerStatus#PENDING}
 * — consent (TK-153) is what flips it to {@code ACTIVE}, so nothing escalates until the invitee
 * agrees. Pairings are deduplicated per (owner, invitee): a repeated invite or a reused deep-link
 * returns the existing one rather than a second row.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NudgerService {

    private final UserRepository userRepository;
    private final NudgerRepository nudgerRepository;

    /**
     * Invites the user behind {@code rawUsername} (a {@code @handle} or bare handle) to be
     * {@code owner}'s nudger. Looks the username up case-insensitively; an unregistered username
     * yields {@link InviteResult.NotRegistered} so the caller can fall back to a deep-link.
     */
    @Transactional
    public InviteResult invite(final User owner, final String rawUsername) {
        final String username = normalizeUsername(rawUsername);
        final Optional<User> invitee = userRepository.findByTgUsernameIgnoreCase(username);
        if (invitee.isEmpty()) {
            return new InviteResult.NotRegistered(username);
        }
        final User nudgerUser = invitee.get();
        if (nudgerUser.getId().equals(owner.getId())) {
            return new InviteResult.SelfInvite();
        }
        final Optional<Nudger> existing =
                nudgerRepository.findByOwnerIdAndNudgerUserId(owner.getId(), nudgerUser.getId());
        if (existing.isPresent()) {
            return new InviteResult.AlreadyInvited(existing.get(), nudgerUser);
        }
        final Nudger created = nudgerRepository.save(Nudger.invite(owner, nudgerUser));
        log.info("User {} invited user {} as nudger {} (PENDING)",
                owner.getId(), nudgerUser.getId(), created.getId());
        return new InviteResult.Invited(created, nudgerUser);
    }

    /**
     * Wires a freshly registered {@code invitee} up as the pending nudger of the owner encoded in an
     * invite deep-link. The decoded {@code ownerId} is re-validated against the users table, so a
     * stale link (deleted owner) is handled gracefully rather than violating the FK.
     */
    @Transactional
    public AcceptResult acceptViaDeepLink(final User invitee, final UUID ownerId) {
        if (ownerId.equals(invitee.getId())) {
            return new AcceptResult.SelfInvite();
        }
        final Optional<User> owner = userRepository.findById(ownerId);
        if (owner.isEmpty()) {
            log.warn("Invite deep-link decoded to unknown owner {} — ignoring", ownerId);
            return new AcceptResult.OwnerGone();
        }
        final Optional<Nudger> existing =
                nudgerRepository.findByOwnerIdAndNudgerUserId(ownerId, invitee.getId());
        if (existing.isPresent()) {
            return new AcceptResult.AlreadyInvited(existing.get(), owner.get());
        }
        final Nudger created = nudgerRepository.save(Nudger.invite(owner.get(), invitee));
        log.info("User {} joined via deep-link as nudger {} for owner {} (PENDING)",
                invitee.getId(), created.getId(), ownerId);
        return new AcceptResult.Invited(created, owner.get());
    }

    /**
     * Records the invitee's Yes/No answer to a consent prompt (TK-153). A {@code PENDING} pairing is
     * flipped to {@code ACTIVE} (accept) or {@code REJECTED} (decline); the resolved owner rides along
     * so the caller can notify them. A pairing that is no longer {@code PENDING} — a replayed or
     * double-tapped callback — yields {@link ConsentResult.AlreadyDecided} and is left untouched, so
     * the transition (and the owner notification) happens exactly once.
     */
    @Transactional
    public ConsentResult consent(final UUID nudgerId, final boolean accept) {
        final Optional<Nudger> found = nudgerRepository.findById(nudgerId);
        if (found.isEmpty()) {
            return new ConsentResult.NotFound();
        }
        final Nudger nudger = found.get();
        if (nudger.getStatus() != NudgerStatus.PENDING) {
            return new ConsentResult.AlreadyDecided(nudger);
        }
        nudger.setStatus(accept ? NudgerStatus.ACTIVE : NudgerStatus.REJECTED);
        final Nudger saved = nudgerRepository.save(nudger);
        final User owner = userRepository.findById(saved.getOwnerId())
                .orElseThrow(() -> new IllegalStateException(
                        "Nudger %s references missing owner %s".formatted(saved.getId(), saved.getOwnerId())));
        log.info("Nudger {} {} the invite from owner {}",
                saved.getId(), accept ? "accepted" : "declined", owner.getId());
        return accept ? new ConsentResult.Accepted(saved, owner) : new ConsentResult.Declined(saved, owner);
    }

    /**
     * The owner's {@code ACTIVE} nudgers as display rows (TK-154 {@code /nudgers list}), resolving each
     * pairing's {@code nudgerUserId} to a username and pairing it with the karma score, ordered by
     * karma (highest first), then username.
     */
    @Transactional(readOnly = true)
    public List<NudgerSummary> listActive(final User owner) {
        final List<Nudger> active =
                nudgerRepository.findByOwnerIdAndStatus(owner.getId(), NudgerStatus.ACTIVE);
        final Map<UUID, String> usernames = userRepository
                .findAllById(active.stream().map(Nudger::getNudgerUserId).toList())
                .stream()
                .collect(Collectors.toMap(User::getId, User::getTgUsername));
        return active.stream()
                .map(n -> new NudgerSummary(usernames.getOrDefault(n.getNudgerUserId(), "?"), n.getKarmaScore()))
                .sorted(Comparator.comparingInt(NudgerSummary::karmaScore).reversed()
                        .thenComparing(NudgerSummary::username))
                .toList();
    }

    /**
     * Read-only resolution of {@code rawUsername} to one of the owner's pairings (TK-154), without
     * mutating it — used by {@code /nudgers remove} to preview the target before its confirmation step.
     * {@link NudgerActionResult.Ok} here means "found", carrying the pairing; the not-found cases match
     * the mutating operations.
     */
    @Transactional(readOnly = true)
    public NudgerActionResult find(final User owner, final String rawUsername) {
        return applyToNudger(owner, rawUsername, NudgerActionResult.Ok::new);
    }

    /** Temporarily deactivates an {@code ACTIVE} pairing (TK-154 {@code /nudgers pause}); only ACTIVE qualifies. */
    @Transactional
    public NudgerActionResult pause(final User owner, final String rawUsername) {
        return applyToNudger(owner, rawUsername, (nudger, invitee) -> {
            if (nudger.getStatus() != NudgerStatus.ACTIVE) {
                return new NudgerActionResult.Unchanged(nudger, invitee);
            }
            nudger.setStatus(NudgerStatus.PAUSED);
            log.info("Owner {} paused nudger {}", owner.getId(), nudger.getId());
            return new NudgerActionResult.Ok(nudgerRepository.save(nudger), invitee);
        });
    }

    /** Reactivates a {@code PAUSED} pairing (TK-154 {@code /nudgers resume}); only PAUSED qualifies. */
    @Transactional
    public NudgerActionResult resume(final User owner, final String rawUsername) {
        return applyToNudger(owner, rawUsername, (nudger, invitee) -> {
            if (nudger.getStatus() != NudgerStatus.PAUSED) {
                return new NudgerActionResult.Unchanged(nudger, invitee);
            }
            nudger.setStatus(NudgerStatus.ACTIVE);
            log.info("Owner {} resumed nudger {}", owner.getId(), nudger.getId());
            return new NudgerActionResult.Ok(nudgerRepository.save(nudger), invitee);
        });
    }

    /**
     * Permanently removes a pairing in any status (TK-154 {@code /nudgers remove}). The hard delete
     * cascades onto its {@code nudge_log} rows via the FK (Flyway {@code V10}), so the audit ledger
     * does not block the removal.
     */
    @Transactional
    public NudgerActionResult remove(final User owner, final String rawUsername) {
        return applyToNudger(owner, rawUsername, (nudger, invitee) -> {
            nudgerRepository.delete(nudger);
            log.info("Owner {} removed nudger {}", owner.getId(), nudger.getId());
            return new NudgerActionResult.Ok(nudger, invitee);
        });
    }

    /**
     * Resolves {@code rawUsername} to one of {@code owner}'s pairings and applies {@code action},
     * funneling the two "no such target" cases — unknown username and known-but-not-a-nudger — through
     * one place so every management subcommand reports them identically.
     */
    private NudgerActionResult applyToNudger(final User owner, final String rawUsername,
            final BiFunction<Nudger, User, NudgerActionResult> action) {
        final String username = normalizeUsername(rawUsername);
        final Optional<User> invitee = userRepository.findByTgUsernameIgnoreCase(username);
        if (invitee.isEmpty()) {
            return new NudgerActionResult.NotRegistered(username);
        }
        final Optional<Nudger> pairing =
                nudgerRepository.findByOwnerIdAndNudgerUserId(owner.getId(), invitee.get().getId());
        if (pairing.isEmpty()) {
            return new NudgerActionResult.NotANudger(username);
        }
        return action.apply(pairing.get(), invitee.get());
    }

    private String normalizeUsername(final String raw) {
        final String trimmed = raw.strip();
        return trimmed.startsWith("@") ? trimmed.substring(1) : trimmed;
    }
}
