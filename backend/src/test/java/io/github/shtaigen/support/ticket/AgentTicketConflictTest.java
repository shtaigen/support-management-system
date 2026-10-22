package io.github.shtaigen.support.ticket;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.sql.Timestamp;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

class AgentTicketConflictTest extends TicketConflictTestSupport {

    @Test
    void ticketClosedDuringReplyGivesLockingConflictAndKeepsNoMessage() throws Exception {
        // The service saw the ticket OPEN, so the domain accepts the reply; only the version check
        // can detect the close.
        beforeMessageSave(() -> inNewTransaction(ticket -> ticket.changeStatus(TicketStatus.CLOSED)));

        MvcTestResult result = mvc.post().uri("/api/agent/tickets/" + ticketId + "/messages")
                .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"Lost reply\"}")
                .with(jwt().jwt(token -> token.subject(Long.toString(agentId)))
                        .authorities(new SimpleGrantedAuthority("ROLE_AGENT")))
                .exchange();

        assertOptimisticLockingConflict(result);

        // The reply and its auto-assignment were rolled back; the close survives unchanged.
        assertThat(messageCount()).isEqualTo(1);
        Map<String, Object> row = ticketRow();
        assertThat(row.get("status")).isEqualTo("CLOSED");
        assertThat(row.get("assignee_id")).isNull();
        assertThat(((Number) row.get("version")).longValue()).isEqualTo(1);
        assertThat(((Timestamp) row.get("last_message_at")).toInstant()).isEqualTo(lastMessageAt);
    }
}
