package io.github.shtaigen.support.ticket;

import io.github.shtaigen.support.user.UserRole;

import java.time.Instant;
import java.util.List;

/**
 * A ticket as its customer sees it (FR-05): no assignee, no version, no author names or emails.
 */
record CustomerTicketResponse(
        Long id,
        String subject,
        TicketCategory category,
        String referenceNumber,
        TicketStatus status,
        Instant createdAt,
        Instant lastMessageAt,
        List<CustomerMessageResponse> messages) {

    record CustomerMessageResponse(Long id, String body, Instant createdAt, UserRole authorRole) {
    }

    /**
     * Call inside the transaction: reads each message's author role.
     *
     * @param thread the ticket's messages, already in thread order (NFR-05)
     */
    static CustomerTicketResponse from(Ticket ticket, List<Message> thread) {
        List<CustomerMessageResponse> messages = thread.stream()
                .map(message -> new CustomerMessageResponse(message.getId(), message.getBody(),
                        message.getCreatedAt(), message.getAuthor().getRole()))
                .toList();
        return new CustomerTicketResponse(ticket.getId(), ticket.getSubject(), ticket.getCategory(),
                ticket.getReferenceNumber(), ticket.getStatus(), ticket.getCreatedAt(),
                ticket.getLastMessageAt(), messages);
    }
}
