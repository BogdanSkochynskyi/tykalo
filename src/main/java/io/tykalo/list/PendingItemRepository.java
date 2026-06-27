package io.tykalo.list;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PendingItemRepository extends JpaRepository<PendingItem, UUID> {

    /** A user's pending items, newest first — the pending screen (TK-257). */
    List<PendingItem> findByUserIdOrderByDeferredAtDesc(UUID userId);

    /**
     * A user's pending items whose captured {@code original_list_tags} overlap any of the given tags,
     * newest first — used to suggest pending items when a new list is created with matching tags
     * (TK-258). Native because tag overlap relies on the Postgres array {@code &&} operator (served by the
     * {@code idx_pending_items_tags} GIN index); {@code tags} is bound as a {@code text[]}.
     */
    @Query(value = """
            SELECT * FROM pending_items
            WHERE user_id = :userId
              AND original_list_tags && CAST(:tags AS text[])
            ORDER BY deferred_at DESC
            """, nativeQuery = true)
    List<PendingItem> findByUserIdAndTagsOverlapping(@Param("userId") UUID userId, @Param("tags") String[] tags);
}
