package io.github.shtaigen.support.ticket;

import java.time.Instant;

/**
 * A customer in the agent's customer summary (FR-08): ticket counts and latest activity, without
 * the password hash or any ticket data. Built by a JPQL constructor expression in
 * {@link TicketRepository}, so only these columns are read.
 *
 * @param activeTicketCount tickets not in CLOSED
 * @param lastMessageAt     the latest {@code lastMessageAt} of the customer's tickets
 */
record AgentCustomerSummary(
        long id,
        String name,
        String email,
        long ticketCount,
        long activeTicketCount,
        Instant lastMessageAt) {
}
