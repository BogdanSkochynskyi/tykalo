package io.tykalo.nudger;

import io.tykalo.user.User;
import io.tykalo.user.UserRepository;
import java.util.Optional;
import java.util.UUID;
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

    private String normalizeUsername(final String raw) {
        final String trimmed = raw.strip();
        return trimmed.startsWith("@") ? trimmed.substring(1) : trimmed;
    }
}
