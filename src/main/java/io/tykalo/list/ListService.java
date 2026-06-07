package io.tykalo.list;

import io.tykalo.user.User;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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

    /** Active (non-archived) lists for the owner; soft-deleted lists are excluded. */
    @Transactional(readOnly = true)
    public List<TaskList> findAllByOwner(final UUID ownerId) {
        return listRepository.findByOwnerIdAndArchivedAtIsNull(ownerId);
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
