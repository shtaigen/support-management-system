package io.github.shtaigen.support.ticket;

import com.jayway.jsonpath.JsonPath;
import io.github.shtaigen.support.user.User;
import io.github.shtaigen.support.user.UserRepository;
import io.github.shtaigen.support.user.UserRole;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.io.UnsupportedEncodingException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

// Runs against the real Postgres; each test rolls back. Other committed customers (for example
// demo data) may exist, so assertions look only at the customers this test created, and every
// response is checked to be sorted by NFR-05 as a whole.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AgentCustomerControllerTest {

    private static final String CUSTOMERS = "/api/agent/customers";

    // Postgres stores microseconds, so every instant written with native SQL is truncated to them.
    private static final Instant T = Instant.parse("2026-01-01T10:00:00.123456789Z").truncatedTo(ChronoUnit.MICROS);

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private TicketRepository tickets;

    @Autowired
    private EntityManager em;

    private User agent;

    private final List<Long> created = new ArrayList<>();

    @BeforeEach
    void createAgent() {
        agent = users.save(new User("ticket-test-agentcustomers-agent@example.com", "hash", "Summary Agent", UserRole.AGENT));
    }

    @Test
    void summaryShowsCountsAndLatestActivity() {
        User customer = customer("values", "Values Customer");
        Ticket open = ticket(TicketStatus.OPEN, customer);
        Ticket answered = ticket(TicketStatus.ANSWERED, customer);
        Ticket replied = ticket(TicketStatus.CUSTOMER_REPLIED, customer);
        Ticket closed = ticket(TicketStatus.CLOSED, customer);
        setLastMessageAt(open, T.plusSeconds(3 * 3600));
        setLastMessageAt(answered, T.plusSeconds(3600));
        setLastMessageAt(replied, T.plusSeconds(2 * 3600));
        setLastMessageAt(closed, T);

        List<Map<String, Object>> own = ownItems(summary(as(agent)));

        assertThat(own).hasSize(1);
        Map<String, Object> item = own.getFirst();
        assertThat(((Number) item.get("id")).longValue()).isEqualTo(customer.getId());
        assertThat(item).containsEntry("name", "Values Customer")
                .containsEntry("email", "ticket-test-agentcustomers-values@example.com");
        assertThat(((Number) item.get("ticketCount")).longValue()).isEqualTo(4);
        assertThat(((Number) item.get("activeTicketCount")).longValue()).isEqualTo(3);
        assertThat(Instant.parse((String) item.get("lastMessageAt"))).isEqualTo(T.plusSeconds(3 * 3600));
    }

    @Test
    void customerWithOnlyClosedTicketsHasNoActiveTickets() {
        User customer = customer("closed", "Closed Customer");
        ticket(TicketStatus.CLOSED, customer);
        ticket(TicketStatus.CLOSED, customer);
        em.flush();

        List<Map<String, Object>> own = ownItems(summary(as(agent)));

        assertThat(own).hasSize(1);
        assertThat(((Number) own.getFirst().get("ticketCount")).longValue()).isEqualTo(2);
        assertThat(((Number) own.getFirst().get("activeTicketCount")).longValue()).isZero();
    }

    @Test
    void customerWithoutTicketsIsNotListed() {
        User withTicket = customer("with", "With Ticket");
        User without = customer("without", "Without Ticket");
        ticket(TicketStatus.OPEN, withTicket);
        em.flush();

        List<Long> ids = ids(summary(as(agent)));

        assertThat(ids).contains(withTicket.getId()).doesNotContain(without.getId());
    }

    @Test
    void lastMessageAtIsTheLatestOfAllTickets() {
        User customer = customer("latest", "Latest Customer");
        Ticket older = ticket(TicketStatus.OPEN, customer);
        Ticket newer = ticket(TicketStatus.OPEN, customer);
        assertThat(older.getId()).isLessThan(newer.getId());
        setLastMessageAt(older, T.plusSeconds(3600));
        setLastMessageAt(newer, T);

        List<Map<String, Object>> own = ownItems(summary(as(agent)));

        assertThat(own).hasSize(1);
        assertThat(Instant.parse((String) own.getFirst().get("lastMessageAt"))).isEqualTo(T.plusSeconds(3600));
    }

    @Test
    void summaryIsOrderedByLastMessageAtThenCustomerId() {
        // a is created first (lowest id) but has the newest activity. Six customers tie, so id
        // decides; without the tie-breaker the database order matches by chance about 1 in 720.
        User a = customer("order-a", "Order A");
        Ticket newest = ticket(TicketStatus.OPEN, a);
        List<Ticket> tied = new ArrayList<>();
        List<Long> tiedIds = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            User customer = customer("order-" + i, "Order " + i);
            tied.add(ticket(TicketStatus.OPEN, customer));
            tiedIds.addFirst(customer.getId());
        }
        setLastMessageAt(newest, T.plusSeconds(3600));
        for (Ticket ticket : tied) {
            setLastMessageAt(ticket, T);
        }
        List<Long> expected = new ArrayList<>(tiedIds);
        expected.addFirst(a.getId());

        List<Long> own = ownItems(summary(as(agent))).stream()
                .map(item -> ((Number) item.get("id")).longValue()).toList();

        assertThat(own).containsExactlyElementsOf(expected);
    }

    @Test
    void ticketOfAnAgentDoesNotListTheAgent() {
        // The domain rejects an agent as the customer, so only native SQL can create this row.
        em.flush();
        int inserted = em.createNativeQuery("insert into tickets (customer_id, subject, category) values (:id, 'Subject', 'GENERAL')")
                .setParameter("id", agent.getId())
                .executeUpdate();
        assertThat(inserted).isEqualTo(1);
        em.clear();

        assertThat(ids(summary(as(agent)))).doesNotContain(agent.getId());
    }

    @Test
    void itemsHaveExactlyTheListedFields() throws Exception {
        User customer = customer("fields", "Fields Customer");
        ticket(TicketStatus.OPEN, customer);
        em.flush();

        MvcTestResult result = summary(as(agent));

        List<Map<String, Object>> items = JsonPath.read(result.getResponse().getContentAsString(), "$");
        assertThat(ownItems(result)).hasSize(1);
        for (Map<String, Object> item : items) {
            assertThat(item).containsOnlyKeys("id", "name", "email", "ticketCount", "activeTicketCount", "lastMessageAt");
        }
        assertThat(result.getResponse().getContentAsString()).doesNotContainIgnoringCase("password")
                .doesNotContain("version");
    }

    @Test
    void noTokenIsUnauthorized() {
        assertThat(mvc.get().uri(CUSTOMERS).exchange()).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void customerIsForbidden() {
        User customer = customer("forbidden", "Forbidden Customer");

        assertThat(mvc.get().uri(CUSTOMERS).with(as(customer)).exchange()).hasStatus(HttpStatus.FORBIDDEN);
    }

    // --- helpers ---

    private User customer(String key, String name) {
        User customer = users.save(new User("ticket-test-agentcustomers-" + key + "@example.com", "hash", name,
                UserRole.CUSTOMER));
        created.add(customer.getId());
        return customer;
    }

    /**
     * Saves the ticket without its messages, which breaks BR-16 (every ticket has a message). That
     * is acceptable only because the summary reads the tickets table alone; do not use this for
     * tests that read threads.
     */
    private Ticket ticket(TicketStatus status, User customer) {
        return tickets.save(TicketFixtures.ticketIn(status, customer, agent));
    }

    /** Flushes pending inserts first, so the UPDATE finds the row, and clears afterwards. */
    private void setLastMessageAt(Ticket ticket, Instant lastMessageAt) {
        em.flush();
        int updated = em.createNativeQuery("update tickets set last_message_at = :lastMessageAt where id = :id")
                .setParameter("lastMessageAt", lastMessageAt)
                .setParameter("id", ticket.getId())
                .executeUpdate();
        assertThat(updated).isEqualTo(1);
        em.clear();
    }

    /** The summary as an agent, checked to be a JSON array sorted by NFR-05 as a whole. */
    private MvcTestResult summary(RequestPostProcessor auth) {
        MvcTestResult result = mvc.get().uri(CUSTOMERS).with(auth).exchange();
        assertThat(result).hasStatusOk().hasContentType(MediaType.APPLICATION_JSON);
        List<Map<String, Object>> items = items(result);
        for (int i = 0; i + 1 < items.size(); i++) {
            Map<String, Object> first = items.get(i);
            Map<String, Object> second = items.get(i + 1);
            assertThat(first.get("lastMessageAt")).as("lastMessageAt of item %d", i).isNotNull();
            assertThat(second.get("lastMessageAt")).as("lastMessageAt of item %d", i + 1).isNotNull();
            Instant firstAt = Instant.parse((String) first.get("lastMessageAt"));
            Instant secondAt = Instant.parse((String) second.get("lastMessageAt"));
            assertThat(firstAt).as("item %d", i).isAfterOrEqualTo(secondAt);
            if (firstAt.equals(secondAt)) {
                assertThat(((Number) first.get("id")).longValue()).as("item %d", i)
                        .isGreaterThan(((Number) second.get("id")).longValue());
            }
        }
        return result;
    }

    private static List<Map<String, Object>> items(MvcTestResult result) {
        try {
            return JsonPath.read(result.getResponse().getContentAsString(), "$");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<Long> ids(MvcTestResult result) {
        return items(result).stream().map(item -> ((Number) item.get("id")).longValue()).toList();
    }

    /** The items of customers this test created, in response order. */
    private List<Map<String, Object>> ownItems(MvcTestResult result) {
        return items(result).stream().filter(item -> created.contains(((Number) item.get("id")).longValue())).toList();
    }

    /** A token for {@code user} with the authority matching their role. */
    private static RequestPostProcessor as(User user) {
        return jwt().jwt(token -> token.subject(user.getId().toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));
    }
}
