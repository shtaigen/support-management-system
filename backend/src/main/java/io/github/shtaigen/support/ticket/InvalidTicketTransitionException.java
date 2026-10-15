package io.github.shtaigen.support.ticket;

/**
 * Thrown when a ticket operation would cause a status transition that section 2 of the
 * requirements does not allow (BR-06, BR-07). Maps to 409 Conflict at the API boundary.
 */
public class InvalidTicketTransitionException extends RuntimeException {

    private final TicketStatus from;
    private final TicketStatus to;

    public InvalidTicketTransitionException(TicketStatus from, TicketStatus to) {
        super("Ticket status cannot change from " + from + " to " + to);
        this.from = from;
        this.to = to;
    }

    public TicketStatus getFrom() {
        return from;
    }

    public TicketStatus getTo() {
        return to;
    }
}
