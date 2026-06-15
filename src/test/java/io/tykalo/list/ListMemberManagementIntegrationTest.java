package io.tykalo.list;

import static org.assertj.core.api.Assertions.assertThat;

import io.tykalo.AbstractIntegrationTest;
import io.tykalo.user.User;
import io.tykalo.user.UserRepository;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Exercises {@link ListMemberService} against the real Flyway-migrated schema (TK-194). The key thing
 * only a real Hibernate/Postgres round-trip catches is that ownership transfer reassigns
 * {@code lists.owner_id} — which requires the column to be mapped {@code updatable} (it was
 * {@code updatable=false} before this ticket). Uses the 1_020_00x tg_chat_id range (the singleton
 * Postgres is shared and never reset between integration-test classes).
 */
class ListMemberManagementIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ListMemberService listMemberService;

    @Autowired
    private ListMemberRepository listMemberRepository;

    @Autowired
    private ListRepository listRepository;

    @Autowired
    private UserRepository userRepository;

    private User savedUser(final long tgChatId, final String username) {
        return userRepository.save(User.create(tgChatId, username, ZoneId.of("Europe/Kyiv"), "uk"));
    }

    @Test
    void transferOwnership_reassignsOwnerId_andSwapsRoles() {
        // Arrange — owner has no member row (legacy owner_id authority), the new owner is an active editor.
        final User owner = savedUser(1_020_001L, "owner");
        final TaskList list = listRepository.save(TaskList.checklist(owner, "Groceries"));
        final User editor = savedUser(1_020_002L, "editor");
        final ListMember editorRow = listMemberRepository.save(
                ListMember.of(list.getId(), editor.getId(), ListMemberRole.EDITOR));

        // Act
        final TransferOwnershipResult result = listMemberService.transferOwnership(owner.getId(), editorRow.getId());

        // Assert
        assertThat(result).isInstanceOf(TransferOwnershipResult.Transferred.class);
        assertThat(listRepository.findById(list.getId()).orElseThrow().getOwnerId()).isEqualTo(editor.getId());
        assertThat(listMemberRepository.findById(editorRow.getId()).orElseThrow().getRole())
                .isEqualTo(ListMemberRole.OWNER);
        assertThat(listMemberRepository.findByListIdAndUserId(list.getId(), owner.getId()))
                .hasValueSatisfying(row -> assertThat(row.getRole()).isEqualTo(ListMemberRole.EDITOR));
    }

    @Test
    void remove_deletesTheMembershipRow() {
        // Arrange
        final User owner = savedUser(1_020_003L, "owner");
        final TaskList list = listRepository.save(TaskList.checklist(owner, "Chores"));
        final User member = savedUser(1_020_004L, "member");
        final ListMember memberRow = listMemberRepository.save(
                ListMember.of(list.getId(), member.getId(), ListMemberRole.MEMBER));

        // Act
        final RemoveMemberResult result = listMemberService.remove(owner.getId(), memberRow.getId());

        // Assert
        assertThat(result).isInstanceOf(RemoveMemberResult.Removed.class);
        assertThat(listMemberRepository.findById(memberRow.getId())).isEmpty();
    }
}
