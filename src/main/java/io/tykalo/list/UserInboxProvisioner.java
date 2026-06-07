package io.tykalo.list;

import io.tykalo.user.UserCreatedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Gives every newly registered user a default Inbox list. Listens to {@link UserCreatedEvent}
 * synchronously, so the inbox is created inside the same transaction as the user — if either insert
 * fails, both roll back. Keeps the dependency one-directional: the list domain reacts to user
 * events rather than the user domain reaching into lists.
 */
@Component
@RequiredArgsConstructor
public class UserInboxProvisioner {

    private final ListService listService;

    @EventListener
    public void onUserCreated(final UserCreatedEvent event) {
        listService.createInbox(event.user());
    }
}
