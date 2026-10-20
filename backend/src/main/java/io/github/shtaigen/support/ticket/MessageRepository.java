package io.github.shtaigen.support.ticket;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Messages are immutable and created only by {@link Ticket#open} and {@link Ticket#postMessage}
 * (BR-17), so this repository has no update or delete. Save only messages returned by those
 * methods, after their ticket.
 */
public interface MessageRepository extends Repository<Message, Long> {

    Message save(Message message);

    /**
     * The thread of a ticket in chronological order (NFR-05), with authors loaded.
     */
    @Query("select m from Message m join fetch m.author where m.ticket.id = :ticketId order by m.createdAt asc, m.id asc")
    List<Message> findThread(@Param("ticketId") Long ticketId);
}
