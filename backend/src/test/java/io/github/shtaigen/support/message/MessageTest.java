package io.github.shtaigen.support.message;

import io.github.shtaigen.support.ticket.Ticket;
import io.github.shtaigen.support.ticket.TicketCategory;
import io.github.shtaigen.support.user.User;
import io.github.shtaigen.support.user.UserRole;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MessageTest {

    private final User customer = new User("customer@example.com", "hash", "Customer", UserRole.CUSTOMER);
    private final Ticket ticket = new Ticket(customer, "Subject", TicketCategory.GENERAL, null);

    @Test
    void bodyIsStoredWithoutStripping() {
        Message message = new Message(ticket, customer, "  Hello\n");

        assertThat(message.getBody()).isEqualTo("  Hello\n");
    }

    @Test
    void blankBodyIsRejected() {
        assertThatThrownBy(() -> new Message(ticket, customer, " \n\t "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void bodyAtMaxLengthIsAccepted() {
        String body = "b".repeat(Message.BODY_MAX_LENGTH);

        assertThat(new Message(ticket, customer, body).getBody()).hasSize(Message.BODY_MAX_LENGTH);
    }

    @Test
    void tooLongBodyIsRejected() {
        String body = "b".repeat(Message.BODY_MAX_LENGTH + 1);

        assertThatThrownBy(() -> new Message(ticket, customer, body))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
