package io.github.shtaigen.support.ticket;

import jakarta.validation.constraints.Size;

/**
 * Input for a customer message (FR-06). The body is checked as sent, because it is stored as sent
 * (BR-11), with the same constraints as the first message in {@link CreateTicketRequest}.
 */
record PostMessageRequest(@NotBlankText @Size(max = Message.BODY_MAX_LENGTH) String body) {
}
