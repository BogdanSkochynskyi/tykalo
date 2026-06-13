package io.tykalo.list;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ListMessageRepository extends JpaRepository<ListMessage, UUID> {

    /** The live message mirroring a list in a specific chat, if one has been sent. */
    Optional<ListMessage> findByListIdAndTgChatId(UUID listId, long tgChatId);

    /** Every live message mirroring a list (one per chat it has been published to). */
    List<ListMessage> findByListId(UUID listId);
}
