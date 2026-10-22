package io.github.shtaigen.support.ticket;

import jakarta.validation.constraints.NotNull;

/**
 * Input for an assignment (FR-12). The version is the one from the agent's FR-09 view (ADR-006).
 */
record AssignTicketRequest(@NotNull Long assigneeId, @NotNull Long version) {
}
