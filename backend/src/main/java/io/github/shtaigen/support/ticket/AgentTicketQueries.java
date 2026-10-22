package io.github.shtaigen.support.ticket;

import java.util.List;

/**
 * Repository fragment for queries that need a dynamic set of predicates.
 */
interface AgentTicketQueries {

    /**
     * The agent queue (FR-07), newest activity first (NFR-05), with customer and assignee read in
     * the same query.
     */
    List<AgentTicketSummary> findAgentQueue(AgentTicketQuery query);
}
