package io.github.shtaigen.support.ticket;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Input for creating a ticket (FR-03). The constraints reject everything {@link Ticket#open}
 * rejects, so invalid input is a 400 and never reaches the domain. The subject and reference number
 * are normalized here as in the domain, so the limits apply to the same strings; the message is
 * checked as sent, because it is stored as sent.
 */
record CreateTicketRequest(
        @NotBlankText @Size(max = Ticket.SUBJECT_MAX_LENGTH) String subject,
        @NotNull TicketCategory category,
        // BR-18. ASCII only before uppercasing, so it is never looser than the domain's check.
        @Size(max = Ticket.REFERENCE_NUMBER_MAX_LENGTH)
        @Pattern(regexp = "[A-Za-z0-9-]+", message = "may contain only letters, digits and hyphens")
        String referenceNumber,
        @NotBlankText @Size(max = Message.BODY_MAX_LENGTH) String message) {

    CreateTicketRequest {
        subject = subject == null ? null : subject.strip();
        referenceNumber = referenceNumber == null || referenceNumber.isBlank() ? null : referenceNumber.strip();
    }
}
