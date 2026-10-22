package io.github.shtaigen.support.ticket;

import io.github.shtaigen.support.user.User;
import io.github.shtaigen.support.user.UserRepository;
import io.github.shtaigen.support.user.UserRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

// BR-15 with real commits. There is deliberately no test transaction: the ticket UPDATE runs only
// when the service's own transaction flushes or commits, and a test transaction would hide whether
// it does. Every row a subclass commits is deleted again, before (leftovers of an aborted run) and
// after.
@SpringBootTest
@AutoConfigureMockMvc
abstract class TicketConflictTestSupport {

    private static final String EMAIL_PATTERN = "ticket-test-conflict-%";

    @Autowired
    protected MockMvcTester mvc;

    // Spring Data repositories are JDK proxies; the spies delegate every unstubbed call to the real
    // repository proxy, including its transaction and exception translation. Stubs are reset after
    // each test (MockReset.AFTER, the default).
    @MockitoSpyBean
    protected MessageRepository messages;

    @MockitoSpyBean
    protected TicketRepository tickets;

    @Autowired
    protected UserRepository users;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    protected long customerId;
    protected long agentId;
    protected long ticketId;
    protected Instant lastMessageAt;

    /** A committed OPEN, unassigned ticket with one message, plus a customer and an agent. */
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

    /**
     * Runs {@code competing} when the service saves its message, then saves it for real. At that
     * point the service has loaded the ticket at version 0 and changed it in memory. Install it in
     * the test, so the setup's save is not affected.
     */
    protected void beforeMessageSave(Runnable competing) {
        Answer<?> realRepository = Mockito.mockingDetails(messages).getMockCreationSettings().getDefaultAnswer();
        doAnswer(invocation -> {
            competing.run();
            return realRepository.answer(invocation);
        }).when(messages).save(any());
    }

    /**
     * Runs {@code competing} when the service flushes the ticket, then flushes for real. At that
     * point the service has passed the version check (ADR-006) and changed the ticket in memory.
     * The competitor's own commit flushes through the EntityManager, not through this method.
     */
    protected void beforeTicketFlush(Runnable competing) {
        Answer<?> realRepository = Mockito.mockingDetails(tickets).getMockCreationSettings().getDefaultAnswer();
        doAnswer(invocation -> {
            competing.run();
            return realRepository.answer(invocation);
        }).when(tickets).flush();
    }

    /** Changes the test ticket in a transaction of its own that commits first. */
    protected void inNewTransaction(Consumer<Ticket> change) {
        TransactionTemplate competing = new TransactionTemplate(transactionManager);
        competing.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        competing.executeWithoutResult(status -> change.accept(tickets.findById(ticketId).orElseThrow()));
    }

    protected static void assertOptimisticLockingConflict(MvcTestResult result) throws Exception {
        assertThat(result).hasStatus(HttpStatus.CONFLICT).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().extractingPath("$.detail")
                .isEqualTo("The data was changed by another request. Reload it and try again.");
        assertThat(result.getMvcResult().getResolvedException()).isInstanceOf(ObjectOptimisticLockingFailureException.class);
        assertThat(result.getResponse().getContentAsString()).doesNotContain(Ticket.class.getName())
                .doesNotContain("version");
    }

    protected Map<String, Object> ticketRow() {
        return jdbc.queryForMap("select status, assignee_id, version, last_message_at from tickets where id = ?", ticketId);
    }

    protected long messageCount() {
        Long count = jdbc.queryForObject("select count(*) from messages where ticket_id = ?", Long.class, ticketId);
        return count == null ? 0 : count;
    }
}
