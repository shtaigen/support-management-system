package io.github.shtaigen.support.ticket;

import io.github.shtaigen.support.user.User;
import io.github.shtaigen.support.user.UserRole;

import java.util.Locale;

/**
 * Test data for ticket tests. Every status is reached only through the public domain methods.
 */
final class TicketFixtures {

    private TicketFixtures() {
    }

    static User customer() {
        return customer("Customer");
    }

    static User customer(String name) {
        return new User(email(name), "hash", name, UserRole.CUSTOMER);
    }

    static User agent(String name) {
        return new User(email(name), "hash", name, UserRole.AGENT);
    }

    static Ticket openTicket(User customer) {
        return Ticket.open(customer, "Subject", TicketCategory.GENERAL, null, "First message").ticket();
    }

    /**
     * A ticket in the given status. ANSWERED and CUSTOMER_REPLIED are assigned to {@code agent}
     * (BR-09); OPEN and CLOSED are unassigned.
     */
    static Ticket ticketIn(TicketStatus status, User customer, User agent) {
        Ticket ticket = openTicket(customer);
        switch (status) {
            case OPEN -> {
            }
            case ANSWERED -> ticket.postMessage(agent, "Agent reply");
            case CUSTOMER_REPLIED -> {
                ticket.postMessage(agent, "Agent reply");
                ticket.postMessage(customer, "Customer reply");
            }
            case CLOSED -> ticket.changeStatus(TicketStatus.CLOSED);
        }
        return ticket;
    }

    private static String email(String name) {
        return name.toLowerCase(Locale.ROOT).replace(' ', '.') + "@example.com";
    }
}
