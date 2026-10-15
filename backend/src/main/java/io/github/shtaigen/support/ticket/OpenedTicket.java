package io.github.shtaigen.support.ticket;

/**
 * A newly opened ticket together with its first message (TR-01, BR-16).
 */
public record OpenedTicket(Ticket ticket, Message firstMessage) {
}
