package io.github.shtaigen.support.ticket;

import io.github.shtaigen.support.user.User;
import io.github.shtaigen.support.user.UserRepository;
import io.github.shtaigen.support.user.UserRole;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Customer ticket operations (FR-03..FR-06). The caller's id comes from the token; every lookup
 * of a ticket is restricted to that customer in the query (BR-01).
 */
@Service
class CustomerTicketService {

    private final TicketRepository tickets;
    private final MessageRepository messages;
    private final UserRepository users;

    CustomerTicketService(TicketRepository tickets, MessageRepository messages, UserRepository users) {
        this.tickets = tickets;
        this.messages = messages;
        this.users = users;
    }

    /**
     * Opens a ticket with its first message in one transaction (TR-01, BR-16).
     *
     * @throws IllegalStateException if the token's user does not exist or is not a customer; the
     *                               MVP never deletes users or changes roles, so this is a bug
     */
    @Transactional
    CustomerTicketResponse create(long customerId, CreateTicketRequest request) {
        User customer = users.findById(customerId)
                .orElseThrow(() -> new IllegalStateException("User " + customerId + " not found"));
        if (customer.getRole() != UserRole.CUSTOMER) {
            throw new IllegalStateException("User " + customerId + " is not a customer");
        }
        OpenedTicket opened = Ticket.open(customer, request.subject(), request.category(),
                request.referenceNumber(), request.message());
        Ticket ticket = tickets.save(opened.ticket());
        Message firstMessage = messages.save(opened.firstMessage());
        return CustomerTicketResponse.from(ticket, List.of(firstMessage));
    }

    /**
     * @throws TicketNotFoundException if the ticket is missing or not owned by the customer
     */
    @Transactional(readOnly = true)
    CustomerTicketResponse get(long customerId, long ticketId) {
        Ticket ticket = tickets.findByIdAndCustomerId(ticketId, customerId)
                .orElseThrow(TicketNotFoundException::new);
        return CustomerTicketResponse.from(ticket, messages.findThread(ticket.getId()));
    }

    /**
     * The customer's tickets, newest activity first (FR-04, NFR-05).
     *
     * @param statuses the status filter; null elements (empty query values) are ignored, and no
     *                 remaining status means all statuses
     */
    @Transactional(readOnly = true)
    List<CustomerTicketSummary> list(long customerId, @Nullable Collection<TicketStatus> statuses) {
        Set<TicketStatus> filter = TicketStatusFilter.of(statuses);
        return filter.isEmpty()
                ? tickets.findSummariesOfCustomer(customerId)
                : tickets.findSummariesOfCustomerInStatuses(customerId, filter);
    }

    /**
     * Adds a customer message to an own ticket (FR-06, TR-03..TR-06). A concurrent change to the
     * ticket makes the commit fail with an optimistic-locking conflict, and the message is rolled
     * back with it (BR-15).
     *
     * @throws TicketNotFoundException if the ticket is missing or not owned by the customer
     */
    @Transactional
    CustomerTicketResponse postMessage(long customerId, long ticketId, PostMessageRequest request) {
        Ticket ticket = tickets.findByIdAndCustomerId(ticketId, customerId)
                .orElseThrow(TicketNotFoundException::new);
        // Ownership is proven by the query, so the ticket's customer is the caller.
        messages.save(ticket.postMessage(ticket.getCustomer(), request.body()));
        return CustomerTicketResponse.from(ticket, messages.findThread(ticket.getId()));
    }
}
