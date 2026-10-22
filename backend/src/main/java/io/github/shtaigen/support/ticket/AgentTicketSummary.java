package io.github.shtaigen.support.ticket;

import org.jspecify.annotations.Nullable;

import java.time.Instant;

/**
 * A ticket in the agent queue (FR-07): with customer and assignee, without version or messages.
 * Built by a Criteria constructor expression in {@link AgentTicketQueriesImpl}, so only these
 * columns are read.
 */
record AgentTicketSummary(
        Long id,
        String subject,
        TicketCategory category,
        String referenceNumber,
        TicketStatus status,
        AgentCustomerRef customer,
        @Nullable AgentAssigneeRef assignee,
        Instant lastMessageAt) {

    /**
     * Flat form for the constructor expression, which cannot build nested records. A null
     * {@code assigneeId} (left join without a match) means the ticket is unassigned.
     */
    AgentTicketSummary(Long id, String subject, TicketCategory category, String referenceNumber,
                       TicketStatus status, Long customerId, String customerName, String customerEmail,
                       @Nullable Long assigneeId, @Nullable String assigneeName, Instant lastMessageAt) {
        this(id, subject, category, referenceNumber, status,
                new AgentCustomerRef(customerId, customerName, customerEmail),
                assigneeId == null ? null : new AgentAssigneeRef(assigneeId, assigneeName),
                lastMessageAt);
    }
}
