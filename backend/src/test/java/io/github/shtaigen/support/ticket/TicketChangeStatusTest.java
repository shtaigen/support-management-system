package io.github.shtaigen.support.ticket;

import io.github.shtaigen.support.user.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.util.stream.Stream;

import static io.github.shtaigen.support.ticket.TicketFixtures.agent;
import static io.github.shtaigen.support.ticket.TicketFixtures.customer;
import static io.github.shtaigen.support.ticket.TicketFixtures.ticketIn;
import static io.github.shtaigen.support.ticket.TicketStatus.ANSWERED;
import static io.github.shtaigen.support.ticket.TicketStatus.CLOSED;
import static io.github.shtaigen.support.ticket.TicketStatus.CUSTOMER_REPLIED;
import static io.github.shtaigen.support.ticket.TicketStatus.OPEN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TicketChangeStatusTest {

    private static final Named<TicketStatus> CONFLICT = Named.of("conflict", null);

    private final User customer = customer();
    private final User agent = agent("Agent");

    static Stream<Arguments> statusMatrix() {
        return Stream.of(
                Arguments.of("BR-07", OPEN, OPEN, CONFLICT),
                Arguments.of("BR-07", OPEN, ANSWERED, CONFLICT),
                Arguments.of("BR-07", OPEN, CUSTOMER_REPLIED, CONFLICT),
                Arguments.of("TR-07", OPEN, CLOSED, CLOSED),
                Arguments.of("BR-07", ANSWERED, OPEN, CONFLICT),
                Arguments.of("BR-07", ANSWERED, ANSWERED, CONFLICT),
                Arguments.of("BR-07", ANSWERED, CUSTOMER_REPLIED, CONFLICT),
                Arguments.of("TR-07", ANSWERED, CLOSED, CLOSED),
                Arguments.of("BR-07", CUSTOMER_REPLIED, OPEN, CONFLICT),
                Arguments.of("BR-07", CUSTOMER_REPLIED, ANSWERED, CONFLICT),
                Arguments.of("BR-07", CUSTOMER_REPLIED, CUSTOMER_REPLIED, CONFLICT),
                Arguments.of("TR-07", CUSTOMER_REPLIED, CLOSED, CLOSED),
                Arguments.of("TR-08", CLOSED, OPEN, OPEN),
                Arguments.of("BR-07", CLOSED, ANSWERED, CONFLICT),
                Arguments.of("BR-07", CLOSED, CUSTOMER_REPLIED, CONFLICT),
                Arguments.of("BR-07", CLOSED, CLOSED, CONFLICT));
    }

    @ParameterizedTest(name = "{0}: {1} -> {2} = {3}")
    @MethodSource("statusMatrix")
    void statusMatrix(String requirementId, TicketStatus from, TicketStatus target, TicketStatus expected) {
        Ticket ticket = ticketIn(from, customer, agent);
        User assignee = ticket.getAssignee();
        Instant lastMessageAt = ticket.getLastMessageAt();

        if (expected == null) {
            assertThatThrownBy(() -> ticket.changeStatus(target))
                    .isInstanceOfSatisfying(InvalidTicketTransitionException.class, e -> {
                        assertThat(e.getFrom()).isEqualTo(from);
                        assertThat(e.getTo()).isEqualTo(target);
                    });
            assertThat(ticket.getStatus()).isEqualTo(from);
        } else {
            ticket.changeStatus(target);
            assertThat(ticket.getStatus()).isEqualTo(expected);
        }
        assertThat(ticket.getAssignee()).isSameAs(assignee);
        assertThat(ticket.getLastMessageAt()).isEqualTo(lastMessageAt);
    }

    @Test
    @DisplayName("TR-07, BR-10: closing keeps the assignee")
    void closeKeepsAssignee() {
        Ticket ticket = ticketIn(ANSWERED, customer, agent);

        ticket.changeStatus(CLOSED);

        assertThat(ticket.getAssignee()).isSameAs(agent);
    }

    @Test
    @DisplayName("TR-08, BR-10: reopening keeps the assignee")
    void reopenKeepsAssignee() {
        Ticket ticket = ticketIn(ANSWERED, customer, agent);
        ticket.changeStatus(CLOSED);

        ticket.changeStatus(OPEN);

        assertThat(ticket.getStatus()).isEqualTo(OPEN);
        assertThat(ticket.getAssignee()).isSameAs(agent);
    }

    @Test
    @DisplayName("FR-11: a null target status is rejected")
    void nullTargetIsRejected() {
        Ticket ticket = ticketIn(OPEN, customer, agent);

        assertThatThrownBy(() -> ticket.changeStatus(null))
                .isInstanceOf(NullPointerException.class);
    }
}
