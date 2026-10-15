package io.github.shtaigen.support.ticket;

import io.github.shtaigen.support.user.User;
import io.github.shtaigen.support.user.UserRole;
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
import static io.github.shtaigen.support.ticket.TicketFixtures.openTicket;
import static io.github.shtaigen.support.ticket.TicketFixtures.ticketIn;
import static io.github.shtaigen.support.ticket.TicketStatus.ANSWERED;
import static io.github.shtaigen.support.ticket.TicketStatus.CLOSED;
import static io.github.shtaigen.support.ticket.TicketStatus.CUSTOMER_REPLIED;
import static io.github.shtaigen.support.ticket.TicketStatus.OPEN;
import static io.github.shtaigen.support.user.UserRole.AGENT;
import static io.github.shtaigen.support.user.UserRole.CUSTOMER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TicketPostMessageTest {

    private static final Named<TicketStatus> CONFLICT = Named.of("conflict", null);

    private final User customer = customer();
    private final User agent = agent("Agent One");
    private final User otherAgent = agent("Agent Two");

    static Stream<Arguments> messageMatrix() {
        return Stream.of(
                Arguments.of("TR-02", OPEN, AGENT, ANSWERED),
                Arguments.of("TR-02", ANSWERED, AGENT, ANSWERED),
                Arguments.of("TR-02", CUSTOMER_REPLIED, AGENT, ANSWERED),
                Arguments.of("BR-06", CLOSED, AGENT, CONFLICT),
                Arguments.of("TR-04", OPEN, CUSTOMER, OPEN),
                Arguments.of("TR-03", ANSWERED, CUSTOMER, CUSTOMER_REPLIED),
                Arguments.of("TR-05", CUSTOMER_REPLIED, CUSTOMER, CUSTOMER_REPLIED),
                Arguments.of("TR-06", CLOSED, CUSTOMER, CUSTOMER_REPLIED));
    }

    @ParameterizedTest(name = "{0}: {2} message on {1} -> {3}")
    @MethodSource("messageMatrix")
    void messageMatrix(String requirementId, TicketStatus from, UserRole authorRole, TicketStatus expected) {
        Ticket ticket = ticketIn(from, customer, agent);
        User author = authorRole == AGENT ? agent : customer;

        if (expected == null) {
            assertConflict(ticket, () -> ticket.postMessage(author, "Hello"), from, ANSWERED);
            return;
        }

        Message message = ticket.postMessage(author, "Hello");

        assertThat(ticket.getStatus()).isEqualTo(expected);
        assertThat(ticket.getLastMessageAt()).isEqualTo(message.getCreatedAt());
        assertThat(message.getTicket()).isSameAs(ticket);
        assertThat(message.getAuthor()).isSameAs(author);
        assertThat(message.getBody()).isEqualTo("Hello");
    }

    @Test
    @DisplayName("BR-13: lastMessageAt equals the createdAt of every new message")
    void lastMessageAtFollowsEveryMessage() {
        OpenedTicket opened = Ticket.open(customer, "Subject", TicketCategory.GENERAL, null, "First");
        Ticket ticket = opened.ticket();
        assertThat(ticket.getLastMessageAt()).isEqualTo(opened.firstMessage().getCreatedAt());

        Message agentReply = ticket.postMessage(agent, "Agent reply");
        assertThat(ticket.getLastMessageAt()).isEqualTo(agentReply.getCreatedAt());

        Message customerReply = ticket.postMessage(customer, "Customer reply");
        assertThat(ticket.getLastMessageAt()).isEqualTo(customerReply.getCreatedAt());

        Message secondAgentReply = ticket.postMessage(otherAgent, "Another reply");
        assertThat(ticket.getLastMessageAt()).isEqualTo(secondAgentReply.getCreatedAt());
    }

    @Test
    @DisplayName("BR-09: the first agent message assigns that agent")
    void firstAgentMessageAssignsAgent() {
        Ticket ticket = openTicket(customer);

        ticket.postMessage(agent, "Reply");

        assertThat(ticket.getAssignee()).isSameAs(agent);
    }

    @Test
    @DisplayName("BR-09: a second agent's message keeps the first assignee")
    void secondAgentMessageKeepsAssignee() {
        Ticket ticket = openTicket(customer);
        ticket.postMessage(agent, "Reply");

        ticket.postMessage(otherAgent, "Another reply");

        assertThat(ticket.getAssignee()).isSameAs(agent);
    }

    @Test
    @DisplayName("BR-09: an agent message keeps a manually assigned agent")
    void agentMessageKeepsManualAssignee() {
        Ticket ticket = openTicket(customer);
        ticket.assignTo(otherAgent);

        ticket.postMessage(agent, "Reply");

        assertThat(ticket.getAssignee()).isSameAs(otherAgent);
    }

    @Test
    @DisplayName("BR-09: a customer message does not assign anyone")
    void customerMessageDoesNotAssign() {
        Ticket ticket = openTicket(customer);

        ticket.postMessage(customer, "More details");

        assertThat(ticket.getAssignee()).isNull();
    }

    @Test
    @DisplayName("TR-06, BR-05, BR-10: a customer message reopens a closed ticket and keeps the assignee")
    void customerReopenKeepsAssignee() {
        Ticket ticket = ticketIn(ANSWERED, customer, agent);
        ticket.changeStatus(CLOSED);

        ticket.postMessage(customer, "Still broken");

        assertThat(ticket.getStatus()).isEqualTo(CUSTOMER_REPLIED);
        assertThat(ticket.getAssignee()).isSameAs(agent);
    }

    @Test
    @DisplayName("BR-01, FR-06: a message from another customer is rejected and changes nothing")
    void otherCustomerIsRejected() {
        Ticket ticket = ticketIn(ANSWERED, customer, agent);
        User otherCustomer = customer("Other Customer");
        Instant lastMessageAt = ticket.getLastMessageAt();

        assertThatThrownBy(() -> ticket.postMessage(otherCustomer, "Hello"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(ticket.getStatus()).isEqualTo(ANSWERED);
        assertThat(ticket.getLastMessageAt()).isEqualTo(lastMessageAt);
    }

    @Test
    @DisplayName("BR-11: a blank body is rejected and changes nothing")
    void blankBodyIsRejected() {
        Ticket ticket = openTicket(customer);
        Instant lastMessageAt = ticket.getLastMessageAt();

        assertThatThrownBy(() -> ticket.postMessage(agent, " \n\t "))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(ticket.getStatus()).isEqualTo(OPEN);
        assertThat(ticket.getAssignee()).isNull();
        assertThat(ticket.getLastMessageAt()).isEqualTo(lastMessageAt);
    }

    @Test
    @DisplayName("BR-11: a body at the maximum length is accepted")
    void bodyAtMaxLengthIsAccepted() {
        String body = "b".repeat(Message.BODY_MAX_LENGTH);

        assertThat(openTicket(customer).postMessage(customer, body).getBody()).hasSize(Message.BODY_MAX_LENGTH);
    }

    @Test
    @DisplayName("BR-11: a too long body is rejected")
    void tooLongBodyIsRejected() {
        String body = "b".repeat(Message.BODY_MAX_LENGTH + 1);
        Ticket ticket = openTicket(customer);

        assertThatThrownBy(() -> ticket.postMessage(customer, body))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("BR-11: the body is stored without stripping")
    void bodyIsStoredWithoutStripping() {
        Message message = openTicket(customer).postMessage(customer, "  Hello\n");

        assertThat(message.getBody()).isEqualTo("  Hello\n");
    }

    @Test
    @DisplayName("BR-12: a null author or body is rejected")
    void nullAuthorOrBodyIsRejected() {
        Ticket ticket = openTicket(customer);

        assertThatThrownBy(() -> ticket.postMessage(null, "Hello"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ticket.postMessage(customer, null))
                .isInstanceOf(NullPointerException.class);
    }

    private static void assertConflict(Ticket ticket, Runnable action, TicketStatus from, TicketStatus to) {
        TicketStatus status = ticket.getStatus();
        User assignee = ticket.getAssignee();
        Instant lastMessageAt = ticket.getLastMessageAt();

        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(InvalidTicketTransitionException.class, e -> {
                    assertThat(e.getFrom()).isEqualTo(from);
                    assertThat(e.getTo()).isEqualTo(to);
                });

        assertThat(ticket.getStatus()).isEqualTo(status);
        assertThat(ticket.getAssignee()).isSameAs(assignee);
        assertThat(ticket.getLastMessageAt()).isEqualTo(lastMessageAt);
    }
}
