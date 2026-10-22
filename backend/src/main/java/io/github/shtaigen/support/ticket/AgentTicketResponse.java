package io.github.shtaigen.support.ticket;

import io.github.shtaigen.support.user.User;
import io.github.shtaigen.support.user.UserRole;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;

/**
 * A ticket as agents see it (FR-09): with customer, assignee and the version that FR-11 and FR-12
 * send back for optimistic locking.
 */
record AgentTicketResponse(
        Long id,
        String subject,
        TicketCategory category,
        String referenceNumber,
        TicketStatus status,
        AgentCustomerRef customer,
        @Nullable AgentAssigneeRef assignee,
        Instant createdAt,
        Instant updatedAt,
        Instant lastMessageAt,
        Long version,
        List<AgentMessageResponse> messages) {

    record AgentMessageResponse(Long id, String body, Instant createdAt, Author author) {
    }

    record Author(String name, UserRole role) {
    }

    /**
     * Call inside the transaction, with the ticket's customer and assignee loaded: reads them and
     * each message's author.
     *
     * @param thread the ticket's messages, already in thread order (NFR-05)
     */
    static AgentTicketResponse from(Ticket ticket, List<Message> thread) {
        List<AgentMessageResponse> messages = thread.stream()
                .map(message -> new AgentMessageResponse(message.getId(), message.getBody(), message.getCreatedAt(),
                        new Author(message.getAuthor().getName(), message.getAuthor().getRole())))
                .toList();
        User customer = ticket.getCustomer();
        User assignee = ticket.getAssignee();
        return new AgentTicketResponse(ticket.getId(), ticket.getSubject(), ticket.getCategory(),
                ticket.getReferenceNumber(), ticket.getStatus(),
                new AgentCustomerRef(customer.getId(), customer.getName(), customer.getEmail()),
                assignee == null ? null : new AgentAssigneeRef(assignee.getId(), assignee.getName()),
                ticket.getCreatedAt(), ticket.getUpdatedAt(), ticket.getLastMessageAt(), ticket.getVersion(),
                messages);
    }
}
