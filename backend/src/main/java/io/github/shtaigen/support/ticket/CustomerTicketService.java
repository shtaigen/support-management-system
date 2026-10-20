package io.github.shtaigen.support.ticket;

import io.github.shtaigen.support.user.User;
import io.github.shtaigen.support.user.UserRepository;
import io.github.shtaigen.support.user.UserRole;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Customer ticket operations (FR-03, FR-05). The caller's id comes from the token; every lookup
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
}
