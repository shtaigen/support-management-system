package io.github.shtaigen.support.ticket;

/**
 * A ticket's customer as agents see it (FR-07, FR-09).
 */
record AgentCustomerRef(Long id, String name, String email) {
}
