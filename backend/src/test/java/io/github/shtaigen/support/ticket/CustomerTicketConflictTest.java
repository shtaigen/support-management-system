package io.github.shtaigen.support.ticket;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.sql.Timestamp;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

class CustomerTicketConflictTest extends TicketConflictTestSupport {

    @Test
    void concurrentChangeGivesConflictAndKeepsNoMessage() throws Exception {
        beforeMessageSave(() -> inNewTransaction(ticket -> ticket.assignTo(users.findById(agentId).orElseThrow())));

        MvcTestResult result = mvc.post().uri("/api/customer/tickets/" + ticketId + "/messages")
                .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"Lost message\"}")
                .with(jwt().jwt(token -> token.subject(Long.toString(customerId)))
                        .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
                .exchange();

        assertOptimisticLockingConflict(result);

        // The message was rolled back; the competing change survives unchanged.
        assertThat(messageCount()).isEqualTo(1);
        Map<String, Object> row = ticketRow();
        assertThat(row.get("status")).isEqualTo("OPEN");
        assertThat(((Number) row.get("assignee_id")).longValue()).isEqualTo(agentId);
        assertThat(((Number) row.get("version")).longValue()).isEqualTo(1);
        assertThat(((Timestamp) row.get("last_message_at")).toInstant()).isEqualTo(lastMessageAt);
    }
}
