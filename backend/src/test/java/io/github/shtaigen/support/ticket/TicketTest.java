package io.github.shtaigen.support.ticket;

import io.github.shtaigen.support.user.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.github.shtaigen.support.ticket.TicketFixtures.agent;
import static io.github.shtaigen.support.ticket.TicketFixtures.customer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TicketTest {

    private final User customer = customer();

    private OpenedTicket openWithReference(String referenceNumber) {
        return Ticket.open(customer, "Subject", TicketCategory.GENERAL, referenceNumber, "Hello");
    }

    private OpenedTicket openWithSubject(String subject) {
        return Ticket.open(customer, subject, TicketCategory.GENERAL, null, "Hello");
    }

    @Test
    @DisplayName("TR-01, BR-03: a new ticket is OPEN and unassigned")
    void newTicketIsOpenAndUnassigned() {
        Ticket ticket = openWithReference(null).ticket();

        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.OPEN);
        assertThat(ticket.getAssignee()).isNull();
        assertThat(ticket.getCustomer()).isSameAs(customer);
    }

    @Test
    @DisplayName("BR-15: a new ticket has no version before it is persisted")
    void newTicketHasNoVersionBeforePersist() {
        assertThat(openWithReference(null).ticket().getVersion()).isNull();
    }

    @Test
    @DisplayName("TR-01, BR-16: the first message is authored by the customer and belongs to the ticket")
    void firstMessageBelongsToTicket() {
        OpenedTicket opened = openWithReference(null);

        assertThat(opened.firstMessage().getTicket()).isSameAs(opened.ticket());
        assertThat(opened.firstMessage().getAuthor()).isSameAs(customer);
        assertThat(opened.firstMessage().getBody()).isEqualTo("Hello");
    }

    @Test
    @DisplayName("TR-01, BR-13: lastMessageAt equals the first message's createdAt")
    void newTicketTimestamps() {
        OpenedTicket opened = openWithReference(null);
        Ticket ticket = opened.ticket();

        assertThat(ticket.getCreatedAt()).isNotNull();
        assertThat(ticket.getUpdatedAt()).isEqualTo(ticket.getCreatedAt());
        assertThat(ticket.getLastMessageAt()).isEqualTo(opened.firstMessage().getCreatedAt());
    }

    @Test
    @DisplayName("FR-03: a user without role CUSTOMER cannot open a ticket")
    void agentCannotOpenTicket() {
        User agent = agent("Agent");

        assertThatThrownBy(() -> Ticket.open(agent, "Subject", TicketCategory.GENERAL, null, "Hello"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("BR-11: a blank first message is rejected")
    void blankFirstMessageIsRejected() {
        assertThatThrownBy(() -> Ticket.open(customer, "Subject", TicketCategory.GENERAL, null, "  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("BR-18: a blank reference number becomes null")
    void blankReferenceNumberBecomesNull() {
        assertThat(openWithReference("   ").ticket().getReferenceNumber()).isNull();
    }

    @Test
    @DisplayName("BR-18: the reference number is stripped and uppercased")
    void referenceNumberIsStrippedAndUppercased() {
        assertThat(openWithReference("  pol-123ab ").ticket().getReferenceNumber()).isEqualTo("POL-123AB");
    }

    @Test
    @DisplayName("BR-18: a reference number with invalid characters is rejected")
    void referenceNumberWithInvalidCharactersIsRejected() {
        assertThatThrownBy(() -> openWithReference("POL 123"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("BR-18: a too long reference number is rejected")
    void tooLongReferenceNumberIsRejected() {
        String referenceNumber = "A".repeat(Ticket.REFERENCE_NUMBER_MAX_LENGTH + 1);

        assertThatThrownBy(() -> openWithReference(referenceNumber))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("FR-03: a blank subject is rejected")
    void blankSubjectIsRejected() {
        assertThatThrownBy(() -> openWithSubject("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("FR-03: a too long subject is rejected")
    void tooLongSubjectIsRejected() {
        String subject = "s".repeat(Ticket.SUBJECT_MAX_LENGTH + 1);

        assertThatThrownBy(() -> openWithSubject(subject))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
