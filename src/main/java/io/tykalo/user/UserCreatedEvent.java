package io.tykalo.user;

/**
 * Published once, synchronously, when a {@link User} is registered on first contact. Listeners run
 * inside the creating transaction, so any provisioning they do (e.g. the per-user Inbox list) is
 * atomic with the user insert.
 */
public record UserCreatedEvent(User user) {
}
