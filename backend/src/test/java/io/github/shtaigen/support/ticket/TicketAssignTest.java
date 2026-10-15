package io.github.shtaigen.support.ticket;

import io.github.shtaigen.support.user.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static io.github.shtaigen.support.ticket.TicketFixtures.agent;
import static io.github.shtaigen.support.ticket.TicketFixtures.customer;
import static io.github.shtaigen.support.ticket.TicketFixtures.openTicket;
import static io.github.shtaigen.support.ticket.TicketFixtures.ticketIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TicketAssignTest {

    private final User customer = customer();
    private final User agent = agent("Agent One");
    private final User otherAgent = agent("Agent Two");

    @Test
    @DisplayName("FR-12, BR-10: assigning an unassigned ticket sets the assignee only")
    void assignUnassignedTicket() {
        Ticket ticket = openTicket(customer);
        Instant lastMessageAt = ticket.getLastMessageAt();

        ticket.assignTo(agent);

        assertThat(ticket.getAssignee()).isSameAs(agent);
        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.OPEN);
        assertThat(ticket.getLastMessageAt()).isEqualTo(lastMessageAt);
    }

    @Test
    @DisplayName("FR-12, BR-10: reassigning replaces the assignee")
    void reassignReplacesAssignee() {
        Ticket ticket = ticketIn(TicketStatus.ANSWERED, customer, agent);

        ticket.assignTo(otherAgent);

        assertThat(ticket.getAssignee()).isSameAs(otherAgent);
        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.ANSWERED);
    }

    // Only the domain state is checked here. That a no-op causes no UPDATE and no version bump
    // (and so no @PreUpdate) needs an integration test with JPA later.
    @Test
    @DisplayName("FR-12: assigning the same agent again is a no-op")
    void assignSameAgentIsNoOp() {
        Ticket ticket = ticketIn(TicketStatus.ANSWERED, customer, agent);
        Instant lastMessageAt = ticket.getLastMessageAt();

        ticket.assignTo(agent);

        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.ANSWERED);
        assertThat(ticket.getAssignee()).isSameAs(agent);
        assertThat(ticket.getLastMessageAt()).isEqualTo(lastMessageAt);
    }

    @Test
    @DisplayName("BR-10: a user without role AGENT cannot be the assignee")
    void customerAsAssigneeIsRejected() {
        Ticket ticket = ticketIn(TicketStatus.ANSWERED, customer, agent);

        assertThatThrownBy(() -> ticket.assignTo(customer))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(ticket.getAssignee()).isSameAs(agent);
    }

    @Test
    @DisplayName("BR-10: unassigning (null assignee) is not supported")
    void nullAssigneeIsRejected() {
        Ticket ticket = ticketIn(TicketStatus.ANSWERED, customer, agent);

        assertThatThrownBy(() -> ticket.assignTo(null))
                .isInstanceOf(NullPointerException.class);

        assertThat(ticket.getAssignee()).isSameAs(agent);
    }

    @Test
    @DisplayName("FR-12: a closed ticket can be assigned and stays CLOSED")
    void closedTicketCanBeAssigned() {
        Ticket ticket = ticketIn(TicketStatus.CLOSED, customer, agent);

        ticket.assignTo(otherAgent);

        assertThat(ticket.getAssignee()).isSameAs(otherAgent);
        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.CLOSED);
    }
}
