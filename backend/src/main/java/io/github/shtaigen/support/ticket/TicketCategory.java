package io.github.shtaigen.support.ticket;

/**
 * Ticket categories. Constants must match the V1 CHECK constraint {@code tickets_category_ck}.
 */
public enum TicketCategory {
    GENERAL,
    POLICY,
    CLAIM
}
