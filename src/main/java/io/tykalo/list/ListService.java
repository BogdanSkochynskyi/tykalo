package io.tykalo.list;

import io.tykalo.user.User;
import java.time.Instant;
import java.util.Collection;
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

    /** Renames a list. The caller validates the name (non-blank, no duplicate per owner) at the boundary. */
    @Transactional
    public TaskList rename(final UUID id, final String newName) {
        if (newName == null || newName.isBlank()) {
            throw new IllegalArgumentException("List name must not be blank");
        }
        final TaskList list = listRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("List not found: " + id));
        list.setName(newName.strip());
        log.info("Renamed list id={} to \"{}\"", id, list.getName());
        return list;
    }

    /**
     * Changes a list's type, realigning its default Nudger policy to the new type. {@code INBOX} is
     * reserved (auto-provisioned per user) and rejected. The caller guards type-specific invariants
     * (e.g. leaving PROJECT while tasks still have Nudgers) before calling.
     */
    @Transactional
    public TaskList changeType(final UUID id, final ListType type) {
        if (type == ListType.INBOX) {
            throw new IllegalArgumentException("Cannot change a list to the reserved INBOX type");
        }
        final TaskList list = listRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("List not found: " + id));
        list.setType(type);
        list.setNudgerDefaultPolicy(type.getDefaultNudgerPolicy());
        log.info("Changed type of list id={} to {}", id, type);
        return list;
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
