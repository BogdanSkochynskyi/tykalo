package io.tykalo.list;

import static org.mockito.Mockito.verify;

import io.tykalo.user.User;
import io.tykalo.user.UserCreatedEvent;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserInboxProvisionerTest {

    @Mock
    private ListService listService;

    @InjectMocks
    private UserInboxProvisioner provisioner;

    @Test
    void onUserCreated_provisionsInbox_forTheNewUser() {
        // Arrange
        final User user = User.create(1L, "owner", ZoneId.of("Europe/Kyiv"), "uk");
        user.setId(UUID.randomUUID());

        // Act
        provisioner.onUserCreated(new UserCreatedEvent(user));

        // Assert
        verify(listService).createInbox(user);
    }
}
