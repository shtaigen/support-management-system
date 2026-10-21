package io.github.shtaigen.support.ticket;

import io.github.shtaigen.support.user.User;
import io.github.shtaigen.support.user.UserRepository;
import io.github.shtaigen.support.user.UserRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

// BR-15 with real commits. There is deliberately no test transaction: the ticket UPDATE runs only
// when the service's own transaction commits, and a test transaction would hide whether it does.
// Every row this test commits is deleted again, before (leftovers of an aborted run) and after.
@SpringBootTest
@AutoConfigureMockMvc
class CustomerTicketConflictTest {

    private static final String EMAIL_PATTERN = "ticket-test-conflict-%";

    @Autowired
    private MockMvcTester mvc;

    // Spring Data repositories are JDK proxies; the spy delegates every unstubbed call to the real
    // repository proxy, including its transaction and exception translation.
    @MockitoSpyBean
    private MessageRepository messages;

    @Autowired
    private TicketRepository tickets;

    @Autowired
    private UserRepository users;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private long customerId;
    private long agentId;
    private long ticketId;
    private Instant lastMessageAt;

    @BeforeEach
    void createTicket() {
        deleteTestData();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            User customer = users.save(new User("ticket-test-conflict-customer@example.com", "hash",
                    "Conflict Customer", UserRole.CUSTOMER));
            User agent = users.save(new User("ticket-test-conflict-agent@example.com", "hash",
                    "Conflict Agent", UserRole.AGENT));
            OpenedTicket opened = Ticket.open(customer, "Subject", TicketCategory.GENERAL, null, "First");
            Ticket ticket = tickets.save(opened.ticket());
            messages.save(opened.firstMessage());
            customerId = customer.getId();
            agentId = agent.getId();
            ticketId = ticket.getId();
            lastMessageAt = ticket.getLastMessageAt();
        });
        // The unstubbed spy saved the first message through the real repository.
        assertThat(messageCount()).isEqualTo(1);
    }

    @AfterEach
    void deleteTestData() {
        // messages_ticket_id_fk is ON DELETE CASCADE (V1), so deleting the tickets removes their messages.
        jdbc.update("delete from tickets where customer_id in (select id from users where email like ?)", EMAIL_PATTERN);
        jdbc.update("delete from users where email like ?", EMAIL_PATTERN);
    }

    @Test
    void concurrentChangeGivesConflictAndKeepsNoMessage() throws Exception {
        // Installed only now, so the setup's save above is not affected.
        Answer<?> realRepository = Mockito.mockingDetails(messages).getMockCreationSettings().getDefaultAnswer();
        doAnswer(invocation -> {
            // The service has loaded the ticket at version 0 and changed it in memory; another
            // transaction now commits a change first.
            competingAssignment();
            return realRepository.answer(invocation);
        }).when(messages).save(any());

        MvcTestResult result = mvc.post().uri("/api/customer/tickets/" + ticketId + "/messages")
                .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"Lost message\"}")
                .with(jwt().jwt(token -> token.subject(Long.toString(customerId)))
                        .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
                .exchange();

        assertThat(result).hasStatus(HttpStatus.CONFLICT).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().extractingPath("$.detail")
                .isEqualTo("The data was changed by another request. Reload it and try again.");
        assertThat(result.getMvcResult().getResolvedException()).isInstanceOf(ObjectOptimisticLockingFailureException.class);
        assertThat(result.getResponse().getContentAsString()).doesNotContain(Ticket.class.getName())
                .doesNotContain("version");

        // The message was rolled back; the competing change survives unchanged.
        assertThat(messageCount()).isEqualTo(1);
        Map<String, Object> row = jdbc.queryForMap(
                "select status, assignee_id, version, last_message_at from tickets where id = ?", ticketId);
        assertThat(row.get("status")).isEqualTo("OPEN");
        assertThat(((Number) row.get("assignee_id")).longValue()).isEqualTo(agentId);
        assertThat(((Number) row.get("version")).longValue()).isEqualTo(1);
        assertThat(((Timestamp) row.get("last_message_at")).toInstant()).isEqualTo(lastMessageAt);
    }

    private void competingAssignment() {
        TransactionTemplate competing = new TransactionTemplate(transactionManager);
        competing.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        competing.executeWithoutResult(status -> tickets.findById(ticketId).orElseThrow()
                .assignTo(users.findById(agentId).orElseThrow()));
    }

    private long messageCount() {
        Long count = jdbc.queryForObject("select count(*) from messages where ticket_id = ?", Long.class, ticketId);
        return count == null ? 0 : count;
    }
}
