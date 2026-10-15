package io.github.shtaigen.support.ticket;

import io.github.shtaigen.support.user.User;
import io.github.shtaigen.support.user.UserRole;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TicketTest {

    private final User customer = new User("customer@example.com", "hash", "Customer", UserRole.CUSTOMER);

    private Ticket ticketWithReference(String referenceNumber) {
        return new Ticket(customer, "Subject", TicketCategory.GENERAL, referenceNumber);
    }

    private Ticket ticketWithSubject(String subject) {
        return new Ticket(customer, subject, TicketCategory.GENERAL, null);
    }

    @Test
    void newTicketIsOpenAndUnassigned() {
        Ticket ticket = ticketWithReference(null);

        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.OPEN);
        assertThat(ticket.getAssignee()).isNull();
    }

    @Test
    void newTicketHasNoVersionBeforePersist() {
        assertThat(ticketWithReference(null).getVersion()).isNull();
    }

    @Test
    void newTicketTimestampsAreEqual() {
        Ticket ticket = ticketWithReference(null);

        assertThat(ticket.getCreatedAt()).isNotNull();
        assertThat(ticket.getUpdatedAt()).isEqualTo(ticket.getCreatedAt());
        assertThat(ticket.getLastMessageAt()).isEqualTo(ticket.getCreatedAt());
    }

    @Test
    void blankReferenceNumberBecomesNull() {
        assertThat(ticketWithReference("   ").getReferenceNumber()).isNull();
    }

    @Test
    void referenceNumberIsStrippedAndUppercased() {
        assertThat(ticketWithReference("  pol-123ab ").getReferenceNumber()).isEqualTo("POL-123AB");
    }

    @Test
    void referenceNumberWithInvalidCharactersIsRejected() {
        assertThatThrownBy(() -> ticketWithReference("POL 123"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tooLongReferenceNumberIsRejected() {
        String referenceNumber = "A".repeat(Ticket.REFERENCE_NUMBER_MAX_LENGTH + 1);

        assertThatThrownBy(() -> ticketWithReference(referenceNumber))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankSubjectIsRejected() {
        assertThatThrownBy(() -> ticketWithSubject("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tooLongSubjectIsRejected() {
        String subject = "s".repeat(Ticket.SUBJECT_MAX_LENGTH + 1);

        assertThatThrownBy(() -> ticketWithSubject(subject))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
