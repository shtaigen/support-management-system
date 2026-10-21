package io.github.shtaigen.support.ticket;

import java.time.Instant;

/**
 * A ticket in its customer's list (FR-04): no assignee, no version, no messages. Built by a JPQL
 * constructor expression in {@link TicketRepository}, so only these columns are read.
 */
record CustomerTicketSummary(
        Long id,
        String subject,
        TicketCategory category,
        String referenceNumber,
        TicketStatus status,
        Instant createdAt,
        Instant lastMessageAt) {
}
