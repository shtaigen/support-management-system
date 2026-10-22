package io.github.shtaigen.support.ticket;

import org.jspecify.annotations.Nullable;

import java.util.Set;

/**
 * The FR-07 queue filters. Each one applies only when present, and all combine with AND.
 *
 * @param statuses   empty means all statuses
 * @param assigneeId only tickets assigned to this agent
 * @param unassigned true means only tickets without an assignee
 * @param customerId only tickets of this customer
 */
record AgentTicketQuery(Set<TicketStatus> statuses, @Nullable Long assigneeId, boolean unassigned,
                        @Nullable Long customerId) {
}
