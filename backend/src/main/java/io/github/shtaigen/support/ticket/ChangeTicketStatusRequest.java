package io.github.shtaigen.support.ticket;

import jakarta.validation.constraints.NotNull;

/**
 * Input for a manual status change (FR-11). The version is the one from the agent's FR-09 view
 * (ADR-006).
 */
record ChangeTicketStatusRequest(@NotNull TicketStatus status, @NotNull Long version) {
}
