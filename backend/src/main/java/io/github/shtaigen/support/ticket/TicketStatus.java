package io.github.shtaigen.support.ticket;

/**
 * Ticket statuses. Constants must match the V1 CHECK constraint {@code tickets_status_ck}.
 */
public enum TicketStatus {
    OPEN,
    ANSWERED,
    CUSTOMER_REPLIED,
    CLOSED
}
