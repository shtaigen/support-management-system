package io.github.shtaigen.support.ticket;

import jakarta.validation.constraints.Size;

/**
 * Input for a message from either role (FR-06, FR-10). The body is checked as sent, because it is stored as sent
 * (BR-11), with the same constraints as the first message in {@link CreateTicketRequest}.
 */
record PostMessageRequest(@NotBlankText @Size(max = Message.BODY_MAX_LENGTH) String body) {
}
