package io.tykalo.list;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tykalo.AbstractIntegrationTest;
import io.tykalo.user.User;
import io.tykalo.user.UserRepository;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Exercises {@link ListMember} and {@link ListMemberRepository} against the real Flyway-migrated
 * schema (V15): role/joined_at defaults, the UNIQUE(list_id, user_id) backstop, the ON DELETE CASCADE
 * FKs, and the derived finders. Uses the 1_010_00x tg_chat_id range (the singleton Postgres is shared
 * and never reset between integration-test classes).
 */
class ListMemberPersistenceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ListMemberRepository listMemberRepository;

    @Autowired
    private ListRepository listRepository;

    @Autowired
    private UserRepository userRepository;

    private User savedUser(final long tgChatId, final String username) {
        return userRepository.save(User.create(tgChatId, username, ZoneId.of("Europe/Kyiv"), "uk"));
    }

    private TaskList savedList(final User owner, final String name) {
        return listRepository.save(TaskList.checklist(owner, name));
    }

    @Test
    void of_persistsMembershipWithRoleAndJoinedAt() {
        // Arrange
        final User owner = savedUser(1_010_001L, "owner");
        final TaskList list = savedList(owner, "Groceries");
        final User member = savedUser(1_010_002L, "member");

        // Act
        final ListMember saved =
                listMemberRepository.save(ListMember.of(list.getId(), member.getId(), ListMemberRole.EDITOR));
        final ListMember reloaded = listMemberRepository.findById(saved.getId()).orElseThrow();

        // Assert
        assertThat(reloaded.getListId()).isEqualTo(list.getId());
        assertThat(reloaded.getUserId()).isEqualTo(member.getId());
        assertThat(reloaded.getRole()).isEqualTo(ListMemberRole.EDITOR);
        assertThat(reloaded.getJoinedAt()).isNotNull();
    }

    @Test
    void unique_rejectsSameUserTwiceInOneList() {
        // Arrange
        final User owner = savedUser(1_010_003L, "owner");
        final TaskList list = savedList(owner, "Trip");
        final User member = savedUser(1_010_004L, "member");
        listMemberRepository.save(ListMember.of(list.getId(), member.getId(), ListMemberRole.MEMBER));

        // Act / Assert — UNIQUE(list_id, user_id) backstop
        assertThatThrownBy(() -> listMemberRepository.saveAndFlush(
                        ListMember.of(list.getId(), member.getId(), ListMemberRole.EDITOR)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void listMember_rejectsMissingList() {
        // Arrange
        final User member = savedUser(1_010_005L, "member");

        // Act / Assert — FK to lists(id) is enforced
        assertThatThrownBy(() -> listMemberRepository.saveAndFlush(
                        ListMember.of(UUID.randomUUID(), member.getId(), ListMemberRole.MEMBER)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingList_cascadesToMembers() {
        // Arrange
        final User owner = savedUser(1_010_006L, "owner");
        final TaskList list = savedList(owner, "Chores");
        listMemberRepository.save(ListMember.owner(list.getId(), owner.getId()));

        // Act
        listRepository.deleteById(list.getId());
        listRepository.flush();

        // Assert — ON DELETE CASCADE removed the membership row
        assertThat(listMemberRepository.findByListId(list.getId())).isEmpty();
    }

    @Test
    void deletingUser_cascadesToMembers() {
        // Arrange
        final User owner = savedUser(1_010_007L, "owner");
        final TaskList list = savedList(owner, "Shared");
        final User member = savedUser(1_010_008L, "member");
        listMemberRepository.save(ListMember.of(list.getId(), member.getId(), ListMemberRole.MEMBER));

        // Act
        userRepository.deleteById(member.getId());
        userRepository.flush();

        // Assert — ON DELETE CASCADE removed the membership row
        assertThat(listMemberRepository.findByUserId(member.getId())).isEmpty();
    }

    @Test
    void findByUserIdAndRole_returnsOnlyMatchingRole() {
        // Arrange
        final User user = savedUser(1_010_009L, "polymath");
        final TaskList owned = savedList(user, "Owned");
        final User otherOwner = savedUser(1_010_010L, "other");
        final TaskList joined = savedList(otherOwner, "Joined");
        listMemberRepository.save(ListMember.owner(owned.getId(), user.getId()));
        listMemberRepository.save(ListMember.of(joined.getId(), user.getId(), ListMemberRole.MEMBER));

        // Act
        final var ownedLists = listMemberRepository.findByUserIdAndRole(user.getId(), ListMemberRole.OWNER);

        // Assert
        assertThat(ownedLists).extracting(ListMember::getListId).containsExactly(owned.getId());
    }

    @Test
    void findByListIdAndUserId_findsTheMembership() {
        // Arrange
        final User owner = savedUser(1_010_011L, "owner");
        final TaskList list = savedList(owner, "Team");
        final User member = savedUser(1_010_012L, "member");
        listMemberRepository.save(ListMember.of(list.getId(), member.getId(), ListMemberRole.EDITOR));

        // Act
        final var found = listMemberRepository.findByListIdAndUserId(list.getId(), member.getId());

        // Assert
        assertThat(found).hasValueSatisfying(m -> assertThat(m.getRole()).isEqualTo(ListMemberRole.EDITOR));
        assertThat(listMemberRepository.existsByListIdAndUserId(list.getId(), member.getId())).isTrue();
        assertThat(listMemberRepository.existsByListIdAndUserId(list.getId(), owner.getId())).isFalse();
    }
}
