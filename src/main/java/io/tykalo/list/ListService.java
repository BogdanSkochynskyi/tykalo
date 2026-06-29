package io.tykalo.list;

import io.tykalo.user.User;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CRUD operations over {@link TaskList}. Deletion is soft: a list keeps its row and only gets an
 * {@code archivedAt} stamp, so foreign keys from tasks (and later nudge logs) stay intact.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ListService {

    private final ListRepository listRepository;
    private final ListMemberRepository listMemberRepository;
    private final ListPermissionService permissionService;

    @Transactional
    public TaskList createList(final User owner, final String name, final ListType type) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("List name must not be blank");
        }
        final TaskList saved = listRepository.save(TaskList.of(owner, name.strip(), type));
        log.info("Created list id={} owner={} type={}", saved.getId(), saved.getOwnerId(), type);
        return saved;
    }

    /** Provisions the per-user Inbox (name "Inbox", type INBOX, Nudgers off). */
    @Transactional
    public TaskList createInbox(final User owner) {
        final TaskList saved = listRepository.save(TaskList.inbox(owner));
        log.info("Created inbox id={} owner={}", saved.getId(), saved.getOwnerId());
        return saved;
    }

    @Transactional(readOnly = true)
    public Optional<TaskList> getById(final UUID id) {
        return listRepository.findById(id);
    }

    /** Like {@link #getById(UUID)} but excludes soft-deleted (archived) lists. */
    @Transactional(readOnly = true)
    public Optional<TaskList> getActiveById(final UUID id) {
        return listRepository.findById(id).filter(list -> list.getArchivedAt() == null);
    }

    /** The owner's active Inbox (auto-provisioned on registration). Empty only if it was archived. */
    @Transactional(readOnly = true)
    public Optional<TaskList> findInbox(final UUID ownerId) {
        return listRepository.findByOwnerIdAndTypeAndArchivedAtIsNull(ownerId, ListType.INBOX).stream()
                .findFirst();
    }

    /** Active (non-archived) lists for the owner; soft-deleted lists are excluded. */
    @Transactional(readOnly = true)
    public List<TaskList> findAllByOwner(final UUID ownerId) {
        return listRepository.findByOwnerIdAndArchivedAtIsNull(ownerId);
    }

    /**
     * Every active list the user can access: their own lists unioned with lists they hold an
     * {@link ListMemberStatus#ACTIVE} {@link ListMember} row on (shared lists, TK-191). Deduped by id
     * with owned lists first, so a list where the user is both {@code owner_id} and has an explicit
     * OWNER row appears once. Archived lists are excluded on both sides; PENDING invites (TK-193) are
     * not yet accessible, so they don't appear here.
     */
    @Transactional(readOnly = true)
    public List<TaskList> findAllAccessibleLists(final UUID userId) {
        final LinkedHashMap<UUID, TaskList> byId = new LinkedHashMap<>();
        listRepository.findByOwnerIdAndArchivedAtIsNull(userId)
                .forEach(list -> byId.put(list.getId(), list));
        final List<UUID> memberListIds =
                listMemberRepository.findByUserIdAndStatus(userId, ListMemberStatus.ACTIVE).stream()
                .map(ListMember::getListId)
                .toList();
        listRepository.findAllById(memberListIds).stream()
                .filter(list -> list.getArchivedAt() == null)
                .forEach(list -> byId.putIfAbsent(list.getId(), list));
        return List.copyOf(byId.values());
    }

    /**
     * Resolves the given list ids to their names in one batch query (no N+1), keyed by id.
     * Ids with no matching row are simply absent from the map; archived lists are included so a
     * task still renders under its (now archived) list name in views.
     */
    @Transactional(readOnly = true)
    public Map<UUID, String> namesByIds(final Collection<UUID> ids) {
        return listRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(TaskList::getId, TaskList::getName));
    }

    /**
     * Looks up an owner's active list by name, case-insensitively. Used to detect name collisions
     * on create and to resolve the target of a delete; archived lists never match.
     */
    @Transactional(readOnly = true)
    public Optional<TaskList> findActiveByName(final UUID ownerId, final String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        final String target = name.strip();
        return listRepository.findByOwnerIdAndArchivedAtIsNull(ownerId).stream()
                .filter(list -> list.getName().equalsIgnoreCase(target))
                .findFirst();
    }

    /**
     * Toggles a list's {@code auto_close} setting (TK-253) on behalf of {@code actorId}, enforcing the
     * edit-list permission at the boundary (OWNER/EDITOR, TK-192). When on, the auto-close detector closes
     * the list silently once every item is done; when off, it instead prompts. Returns the updated list.
     */
    @Transactional
    public TaskList setAutoClose(final UUID actorId, final UUID listId, final boolean autoClose) {
        permissionService.requireCanEditList(actorId, listId);
        final TaskList list = listRepository.findById(listId)
                .orElseThrow(() -> new IllegalArgumentException("List not found: " + listId));
        list.setAutoClose(autoClose);
        log.info("Set auto-close of list id={} to {} by actor={}", listId, autoClose, actorId);
        return list;
    }

    /**
     * Appends {@code tag} to a list's tags (TK-259) on behalf of {@code actorId}, enforcing the
     * edit-list permission at the boundary (OWNER/EDITOR, TK-192). Idempotent: an already-present tag
     * is left untouched. The tag is expected pre-normalized by {@link ListTags#validate}. Returns the
     * updated list.
     */
    @Transactional
    public TaskList addTag(final UUID actorId, final UUID listId, final String tag) {
        permissionService.requireCanEditList(actorId, listId);
        final TaskList list = listRepository.findById(listId)
                .orElseThrow(() -> new IllegalArgumentException("List not found: " + listId));
        if (!list.getTags().contains(tag)) {
            list.getTags().add(tag);
            log.info("Added tag '{}' to list id={} by actor={}", tag, listId, actorId);
        }
        return list;
    }

    /**
     * Removes {@code tag} from a list's tags (TK-259) on behalf of {@code actorId}, enforcing the
     * edit-list permission at the boundary (OWNER/EDITOR, TK-192). A no-op if the tag is absent.
     * Returns the updated list.
     */
    @Transactional
    public TaskList removeTag(final UUID actorId, final UUID listId, final String tag) {
        permissionService.requireCanEditList(actorId, listId);
        final TaskList list = listRepository.findById(listId)
                .orElseThrow(() -> new IllegalArgumentException("List not found: " + listId));
        if (list.getTags().remove(tag)) {
            log.info("Removed tag '{}' from list id={} by actor={}", tag, listId, actorId);
        }
        return list;
    }

    /**
     * Deletes a list on behalf of {@code actorId}, enforcing the OWNER-only permission at the
     * boundary (TK-192) before delegating to {@link #deleteList(UUID)}. Throws
     * {@link ListPermissionDeniedException} if the actor may not delete the list.
     */
    @Transactional
    public void deleteList(final UUID actorId, final UUID listId) {
        permissionService.requireCanDeleteList(actorId, listId);
        deleteList(listId);
    }

    @Transactional
    public void deleteList(final UUID id) {
        final TaskList list = listRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("List not found: " + id));
        if (list.getArchivedAt() == null) {
            list.setArchivedAt(Instant.now());
            log.info("Soft-deleted list id={}", id);
        }
    }
}
