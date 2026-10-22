package io.github.shtaigen.support.ticket;

import com.jayway.jsonpath.JsonPath;
import io.github.shtaigen.support.user.User;
import io.github.shtaigen.support.user.UserRepository;
import io.github.shtaigen.support.user.UserRole;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

// Runs against the real Postgres; each test rolls back. Other committed tickets (for example demo
// data) may exist, so queue assertions without a customer or assignee filter look only at the
// tickets this test created, and check that every returned item matches the filter.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AgentTicketControllerTest {

    private static final String TICKETS = "/api/agent/tickets";

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private TicketRepository tickets;

    @Autowired
    private MessageRepository messages;

    @Autowired
    private EntityManager em;

    private User customer;
    private User otherCustomer;
    private User agent;
    private User otherAgent;

    private final List<Long> created = new ArrayList<>();

    @BeforeEach
    void createUsers() {
        customer = users.save(new User("ticket-test-agentapi-customer@example.com", "hash", "Queue Customer", UserRole.CUSTOMER));
        otherCustomer = users.save(new User("ticket-test-agentapi-other@example.com", "hash", "Other Customer", UserRole.CUSTOMER));
        agent = users.save(new User("ticket-test-agentapi-agent@example.com", "hash", "Queue Agent", UserRole.AGENT));
        otherAgent = users.save(new User("ticket-test-agentapi-agent2@example.com", "hash", "Second Agent", UserRole.AGENT));
    }

    // --- FR-07 queue ---

    @Test
    void queueShowsTicketsOfAllCustomers() {
        Ticket own = ticketIn(TicketStatus.OPEN, customer, null);
        Ticket other = ticketIn(TicketStatus.ANSWERED, otherCustomer, agent);
        em.clear();

        assertThat(ownIds(list(as(agent), ""))).containsExactlyInAnyOrder(own.getId(), other.getId());
    }

    @Test
    void queueIsOrderedByLastMessageAtThenId() {
        // newest is created first (lowest id); older and olderTie share a timestamp, so id decides.
        Ticket newest = ticketIn(TicketStatus.OPEN, customer, null);
        Ticket older = ticketIn(TicketStatus.OPEN, otherCustomer, null);
        Ticket olderTie = ticketIn(TicketStatus.OPEN, customer, null);
        Instant tie = Instant.parse("2026-01-01T10:00:00Z");
        setLastMessageAt(newest, tie.plusSeconds(3600));
        setLastMessageAt(older, tie);
        setLastMessageAt(olderTie, tie);
        em.clear();

        assertThat(ownIds(list(as(agent), ""))).containsExactly(newest.getId(), olderTie.getId(), older.getId());
    }

    @Test
    void queueFiltersByStatus() throws Exception {
        Ticket open = ticketIn(TicketStatus.OPEN, customer, null);
        Ticket answered = ticketIn(TicketStatus.ANSWERED, otherCustomer, agent);
        Ticket replied = ticketIn(TicketStatus.CUSTOMER_REPLIED, customer, agent);
        Ticket closed = ticketIn(TicketStatus.CLOSED, customer, null);
        em.clear();

        MvcTestResult single = list(as(agent), "?status=ANSWERED");
        assertThat(ownIds(single)).containsExactly(answered.getId());
        assertThat(statuses(single)).containsOnly("ANSWERED");

        MvcTestResult repeated = list(as(agent), "?status=OPEN&status=CLOSED");
        assertThat(ownIds(repeated)).containsExactlyInAnyOrder(open.getId(), closed.getId());
        assertThat(statuses(repeated)).containsOnly("OPEN", "CLOSED");

        MvcTestResult comma = list(as(agent), "?status=CUSTOMER_REPLIED,CLOSED,");
        assertThat(ownIds(comma)).containsExactlyInAnyOrder(replied.getId(), closed.getId());
        assertThat(statuses(comma)).containsOnly("CUSTOMER_REPLIED", "CLOSED");

        assertThat(ownIds(list(as(agent), "?status=")))
                .containsExactlyInAnyOrder(open.getId(), answered.getId(), replied.getId(), closed.getId());
    }

    @Test
    void queueFiltersByAssignee() {
        Ticket mine = ticketIn(TicketStatus.ANSWERED, customer, agent);
        Ticket closedMine = ticketIn(TicketStatus.CLOSED, otherCustomer, agent);
        ticketIn(TicketStatus.ANSWERED, customer, otherAgent);
        ticketIn(TicketStatus.OPEN, customer, null);
        em.clear();

        // The agent is new, so no other data can match.
        assertThat(ids(list(as(agent), "?assigneeId=" + agent.getId())))
                .containsExactlyInAnyOrder(mine.getId(), closedMine.getId());
    }

    @Test
    void queueFiltersUnassigned() throws Exception {
        Ticket open = ticketIn(TicketStatus.OPEN, customer, null);
        Ticket closed = ticketIn(TicketStatus.CLOSED, otherCustomer, null);
        ticketIn(TicketStatus.ANSWERED, customer, agent);
        em.clear();

        MvcTestResult result = list(as(agent), "?unassigned=true");

        assertThat(ownIds(result)).containsExactlyInAnyOrder(open.getId(), closed.getId());
        List<Object> assignees = JsonPath.read(result.getResponse().getContentAsString(), "$[*].assignee");
        assertThat(assignees).containsOnlyNulls();
    }

    @Test
    void unassignedFalseIsTheSameAsAbsent() throws Exception {
        ticketIn(TicketStatus.OPEN, customer, null);
        ticketIn(TicketStatus.ANSWERED, customer, agent);
        em.clear();

        String absent = list(as(agent), "").getResponse().getContentAsString();
        String unassignedFalse = list(as(agent), "?unassigned=false").getResponse().getContentAsString();

        assertThat(unassignedFalse).isEqualTo(absent);
    }

    @Test
    void queueFiltersByCustomer() {
        Ticket open = ticketIn(TicketStatus.OPEN, customer, null);
        Ticket answered = ticketIn(TicketStatus.ANSWERED, customer, agent);
        ticketIn(TicketStatus.OPEN, otherCustomer, null);
        em.clear();

        assertThat(ids(list(as(agent), "?customerId=" + customer.getId())))
                .containsExactlyInAnyOrder(open.getId(), answered.getId());
    }

    @Test
    void filtersCombineWithAnd() {
        Ticket answeredMine = ticketIn(TicketStatus.ANSWERED, customer, agent);
        Ticket closedMine = ticketIn(TicketStatus.CLOSED, customer, agent);
        Ticket openUnassigned = ticketIn(TicketStatus.OPEN, customer, null);
        ticketIn(TicketStatus.CUSTOMER_REPLIED, customer, agent);
        ticketIn(TicketStatus.ANSWERED, customer, otherAgent);
        ticketIn(TicketStatus.CLOSED, customer, null);
        ticketIn(TicketStatus.ANSWERED, otherCustomer, agent);
        em.clear();

        String byCustomer = "&customerId=" + customer.getId();
        assertThat(ids(list(as(agent), "?status=ANSWERED,CLOSED&assigneeId=" + agent.getId() + byCustomer)))
                .containsExactlyInAnyOrder(answeredMine.getId(), closedMine.getId());
        assertThat(ids(list(as(agent), "?status=OPEN&status=CUSTOMER_REPLIED&unassigned=true" + byCustomer)))
                .containsExactly(openUnassigned.getId());
        assertThat(ids(list(as(agent), "?status=OPEN&assigneeId=" + agent.getId() + "&unassigned=true" + byCustomer)))
                .isEmpty();
    }

    @Test
    void assigneeIdWithUnassignedTrueIsEmpty() {
        ticketIn(TicketStatus.OPEN, customer, null);
        ticketIn(TicketStatus.ANSWERED, customer, agent);
        em.clear();

        assertThat(ids(list(as(agent), "?assigneeId=" + agent.getId() + "&unassigned=true"))).isEmpty();
    }

    @Test
    void idsOfTheWrongRoleMatchNothing() {
        ticketIn(TicketStatus.ANSWERED, customer, agent);
        em.clear();

        assertThat(ids(list(as(agent), "?assigneeId=" + customer.getId()))).isEmpty();
        assertThat(ids(list(as(agent), "?customerId=" + agent.getId()))).isEmpty();
    }

    @Test
    void invalidFilterValuesAreRejectedWithoutEchoingThem() throws Exception {
        String[][] cases = {
                {"status", "?status=NOT_A_STATUS_MARKER", "NOT_A_STATUS_MARKER"},
                {"status", "?status=OPEN,BOGUS_MARKER", "BOGUS_MARKER"},
                {"status", "?status=closed", "closed"},
                {"assigneeId", "?assigneeId=abc-marker", "abc-marker"},
                {"assigneeId", "?assigneeId=99999999999999999999", "99999999999999999999"},
                {"customerId", "?customerId=cust-marker", "cust-marker"},
                {"customerId", "?customerId=1.5", "1.5"},
                {"unassigned", "?unassigned=maybe-marker", "maybe-marker"},
        };
        for (String[] c : cases) {
            MvcTestResult result = list(as(agent), c[1]);

            assertThat(result).as(c[1]).hasStatus(HttpStatus.BAD_REQUEST).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
            assertThat(result).as(c[1]).bodyJson().extractingPath("$.errors[*].field").asArray().containsOnly(c[0]);
            assertThat(result.getResponse().getContentAsString()).as(c[1]).doesNotContain(c[2]);
        }
    }

    @Test
    void queueItemsHaveExactlyTheListedFields() throws Exception {
        Ticket assigned = ticketIn(TicketStatus.ANSWERED, customer, agent);
        ticketIn(TicketStatus.OPEN, customer, null);
        em.clear();

        String body = list(as(agent), "?customerId=" + customer.getId()).getResponse().getContentAsString();

        List<Map<String, Object>> items = JsonPath.read(body, "$");
        assertThat(items).hasSize(2);
        for (Map<String, Object> item : items) {
            assertThat(item).containsOnlyKeys("id", "subject", "category", "referenceNumber", "status", "customer",
                    "assignee", "lastMessageAt");
            assertThat(item.get("customer")).isEqualTo(Map.of("id", customer.getId().intValue(),
                    "name", "Queue Customer", "email", "ticket-test-agentapi-customer@example.com"));
        }
        Map<String, Object> answered = items.stream()
                .filter(item -> ((Number) item.get("id")).longValue() == assigned.getId()).findFirst().orElseThrow();
        assertThat(answered.get("assignee")).isEqualTo(Map.of("id", agent.getId().intValue(), "name", "Queue Agent"));
        assertThat(answered).containsEntry("status", "ANSWERED").containsEntry("subject", "Subject")
                .containsEntry("category", "GENERAL");
        Map<String, Object> open = items.stream().filter(item -> item != answered).findFirst().orElseThrow();
        assertThat(open.get("assignee")).isNull();
        assertThat(body).doesNotContain("version").doesNotContain("messages");
    }

    // --- FR-09 view ---

    @Test
    void viewShowsAllFieldsAndTheThread() throws Exception {
        OpenedTicket opened = Ticket.open(customer, "Claim subject", TicketCategory.CLAIM, "ab-7", "First");
        Ticket ticket = tickets.save(opened.ticket());
        messages.save(opened.firstMessage());
        messages.save(ticket.postMessage(agent, "Agent reply"));
        messages.save(ticket.postMessage(customer, "Customer reply"));
        em.flush();
        em.clear();
        Ticket stored = tickets.findById(ticket.getId()).orElseThrow();

        MvcTestResult result = get(as(otherAgent), ticket.getId());

        assertThat(result).hasStatusOk().hasContentType(MediaType.APPLICATION_JSON);
        String body = result.getResponse().getContentAsString();
        Map<String, Object> json = JsonPath.read(body, "$");
        assertThat(json).containsOnlyKeys("id", "subject", "category", "referenceNumber", "status", "customer",
                "assignee", "createdAt", "updatedAt", "lastMessageAt", "version", "messages");
        assertThat(json).containsEntry("subject", "Claim subject").containsEntry("category", "CLAIM")
                .containsEntry("referenceNumber", "AB-7").containsEntry("status", "CUSTOMER_REPLIED");
        assertThat(((Number) json.get("id")).longValue()).isEqualTo(ticket.getId());
        assertThat(((Number) json.get("version")).longValue()).isEqualTo(stored.getVersion());
        assertThat(json.get("customer")).isEqualTo(Map.of("id", customer.getId().intValue(),
                "name", "Queue Customer", "email", "ticket-test-agentapi-customer@example.com"));
        assertThat(json.get("assignee")).isEqualTo(Map.of("id", agent.getId().intValue(), "name", "Queue Agent"));
        assertThat(Instant.parse((String) json.get("createdAt"))).isEqualTo(stored.getCreatedAt());
        assertThat(Instant.parse((String) json.get("updatedAt"))).isEqualTo(stored.getUpdatedAt());
        assertThat(Instant.parse((String) json.get("lastMessageAt"))).isEqualTo(stored.getLastMessageAt());

        List<Map<String, Object>> thread = JsonPath.read(body, "$.messages");
        assertThat(thread).extracting(m -> m.get("body")).containsExactly("First", "Agent reply", "Customer reply");
        assertThat(thread.getFirst()).containsOnlyKeys("id", "body", "createdAt", "author");
        assertThat(thread).extracting(m -> m.get("author")).containsExactly(
                Map.of("name", "Queue Customer", "role", "CUSTOMER"),
                Map.of("name", "Queue Agent", "role", "AGENT"),
                Map.of("name", "Queue Customer", "role", "CUSTOMER"));
        assertThat(json.get("lastMessageAt")).isEqualTo(thread.getLast().get("createdAt"));
    }

    @Test
    void viewOfAnUnassignedTicketHasNullAssignee() {
        Ticket ticket = ticketIn(TicketStatus.OPEN, customer, null);
        em.clear();

        MvcTestResult result = get(as(agent), ticket.getId());

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().hasPath("$.assignee");
        assertThat(result).bodyJson().extractingPath("$.assignee").isNull();
    }

    @Test
    void threadIsOrderedByCreatedAtThenId() throws Exception {
        Ticket ticket = ticketIn(TicketStatus.CUSTOMER_REPLIED, customer, agent);
        // All three messages get the same timestamp, so the id decides.
        em.createNativeQuery("update messages set created_at = :at where ticket_id = :id")
                .setParameter("at", Instant.parse("2026-01-01T10:00:00Z"))
                .setParameter("id", ticket.getId())
                .executeUpdate();
        em.clear();

        String body = get(as(agent), ticket.getId()).getResponse().getContentAsString();

        assertThat(JsonPath.<List<String>>read(body, "$.messages[*].body"))
                .containsExactly("First", "Agent reply", "Customer reply");
        List<Number> ids = JsonPath.read(body, "$.messages[*].id");
        assertThat(ids.stream().map(Number::longValue).toList()).isSorted();
    }

    @Test
    void missingTicketIsNotFound() {
        MvcTestResult result = get(as(agent), Long.MAX_VALUE);

        assertNotFound(result);
    }

    @Test
    void nonNumericIdIsRejected() {
        MvcTestResult result = get(as(agent), "abc-marker");

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.errors[*].field").asArray().containsOnly("id");
    }

    // --- FR-10 reply ---

    @ParameterizedTest
    @EnumSource(value = TicketStatus.class, names = {"OPEN", "ANSWERED", "CUSTOMER_REPLIED"})
    void agentReplySetsAnswered(TicketStatus from) {
        Ticket ticket = ticketIn(from, customer, from == TicketStatus.OPEN ? null : agent);
        em.clear();

        MvcTestResult result = postMessage(as(agent), ticket.getId(), messageJson("Reply"));

        assertThat(result).hasStatusOk().hasContentType(MediaType.APPLICATION_JSON);
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("ANSWERED");
        em.flush();
        em.clear();
        assertThat(tickets.findById(ticket.getId()).orElseThrow().getStatus()).isEqualTo(TicketStatus.ANSWERED);
    }

    @Test
    void replyToUnassignedTicketAssignsTheCaller() {
        Ticket ticket = ticketIn(TicketStatus.OPEN, customer, null);
        long version = ticket.getVersion();
        Instant updatedAt = ticket.getUpdatedAt();
        em.clear();

        MvcTestResult result = postMessage(as(otherAgent), ticket.getId(), messageJson("Reply"));

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.assignee")
                .isEqualTo(Map.of("id", otherAgent.getId().intValue(), "name", "Second Agent"));
        em.flush();
        em.clear();
        Ticket stored = tickets.findById(ticket.getId()).orElseThrow();
        assertThat(stored.getAssignee().getId()).isEqualTo(otherAgent.getId());
        assertThat(stored.getVersion()).isEqualTo(version + 1);
        assertThat(stored.getUpdatedAt()).isAfter(updatedAt);
    }

    @Test
    void replyToTicketOfAnotherAgentKeepsTheAssignee() {
        Ticket ticket = ticketIn(TicketStatus.CUSTOMER_REPLIED, customer, agent);
        em.clear();

        MvcTestResult result = postMessage(as(otherAgent), ticket.getId(), messageJson("Stepping in"));

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.assignee")
                .isEqualTo(Map.of("id", agent.getId().intValue(), "name", "Queue Agent"));
        assertThat(result).bodyJson().extractingPath("$.messages[-1].author")
                .isEqualTo(Map.of("name", "Second Agent", "role", "AGENT"));
        em.flush();
        em.clear();
        assertThat(tickets.findById(ticket.getId()).orElseThrow().getAssignee().getId()).isEqualTo(agent.getId());
    }

    @Test
    void replyToClosedTicketIsConflictAndChangesNothing() throws Exception {
        Ticket ticket = ticketIn(TicketStatus.CLOSED, customer, agent);
        long version = ticket.getVersion();
        Instant updatedAt = ticket.getUpdatedAt();
        Instant lastMessageAt = ticket.getLastMessageAt();
        int threadSize = messages.findThread(ticket.getId()).size();
        em.clear();

        MvcTestResult result = postMessage(as(otherAgent), ticket.getId(), messageJson("Too late"));

        assertThat(result).hasStatus(HttpStatus.CONFLICT).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().extractingPath("$.detail")
                .isEqualTo("The ticket's current status does not allow this action.");
        assertThat(result.getMvcResult().getResolvedException()).isInstanceOf(InvalidTicketTransitionException.class);
        assertThat(result.getResponse().getContentAsString()).doesNotContain("CLOSED").doesNotContain("ANSWERED")
                .doesNotContain("version");

        // The failed reply marked the test transaction rollback-only, so it is not flushed again;
        // the domain threw before changing anything, and the message was never saved.
        em.clear();
        Ticket stored = tickets.findById(ticket.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(TicketStatus.CLOSED);
        assertThat(stored.getAssignee().getId()).isEqualTo(agent.getId());
        assertThat(stored.getVersion()).isEqualTo(version);
        assertThat(stored.getUpdatedAt()).isEqualTo(updatedAt);
        assertThat(stored.getLastMessageAt()).isEqualTo(lastMessageAt);
        assertThat(messages.findThread(ticket.getId())).hasSize(threadSize);
    }

    @Test
    void replyIsLastInThreadAndSetsLastMessageAt() throws Exception {
        Ticket ticket = ticketIn(TicketStatus.CUSTOMER_REPLIED, customer, agent);
        em.clear();

        MvcTestResult result = postMessage(as(agent), ticket.getId(), messageJson("  Agent\nanswer  "));

        String body = result.getResponse().getContentAsString();
        List<Map<String, Object>> thread = JsonPath.read(body, "$.messages");
        assertThat(thread).hasSize(4);
        assertThat(thread.getLast()).containsEntry("body", "  Agent\nanswer  ")
                .containsEntry("author", Map.of("name", "Queue Agent", "role", "AGENT"));
        assertThat(JsonPath.<String>read(body, "$.lastMessageAt")).isEqualTo(thread.getLast().get("createdAt"));
    }

    @Test
    void postResponseEqualsReadResponse() throws Exception {
        // Unassigned, so the reply changes status, assignee, version and updatedAt.
        Ticket ticket = ticketIn(TicketStatus.OPEN, customer, null);
        em.clear();

        MvcTestResult posted = postMessage(as(agent), ticket.getId(), messageJson("Body"));
        assertThat(posted).hasStatusOk();
        // The GET must read from the database, not from the shared persistence context.
        em.flush();
        em.clear();
        MvcTestResult read = get(as(agent), ticket.getId());

        assertThat(read).hasStatusOk();
        assertThat(read.getResponse().getContentAsString()).isEqualTo(posted.getResponse().getContentAsString());
    }

    @Test
    void messageBodyAtTheLimitIsAccepted() {
        Ticket ticket = ticketIn(TicketStatus.OPEN, customer, null);

        assertThat(postMessage(as(agent), ticket.getId(), messageJson("m".repeat(Message.BODY_MAX_LENGTH))))
                .hasStatusOk();
    }

    @Test
    void invalidMessageBodiesAreRejected() {
        long id = ticketIn(TicketStatus.OPEN, customer, null).getId();
        String tooLong = "m".repeat(Message.BODY_MAX_LENGTH + 1);
        String paddedTooLong = " " + "m".repeat(Message.BODY_MAX_LENGTH);

        assertValidationError(postMessage(as(agent), id, "{}"), "body", null);
        assertValidationError(postMessage(as(agent), id, messageJson(null)), "body", null);
        assertValidationError(postMessage(as(agent), id, messageJson("  \n\t ")), "body", null);
        assertValidationError(postMessage(as(agent), id, messageJson("body-marker\u0000x")), "body", "body-marker");
        assertValidationError(postMessage(as(agent), id, messageJson(tooLong)), "body", tooLong);
        assertValidationError(postMessage(as(agent), id, messageJson(paddedTooLong)), "body", paddedTooLong.strip());
        assertThat(messages.findThread(id)).hasSize(1);
    }

    @Test
    void replyToMissingTicketIsNotFound() {
        assertNotFound(postMessage(as(agent), Long.MAX_VALUE, messageJson("Hi")));
    }

    // --- FR-11 status change ---

    @ParameterizedTest
    @EnumSource(value = TicketStatus.class, names = {"OPEN", "ANSWERED", "CUSTOMER_REPLIED"})
    void agentClosesTicketAndKeepsTheAssignee(TicketStatus from) throws Exception {
        User assignee = from == TicketStatus.OPEN ? null : agent;
        Ticket ticket = ticketIn(from, customer, assignee);
        long version = ticket.getVersion();
        Instant updatedAt = ticket.getUpdatedAt();
        em.clear();

        MvcTestResult result = putStatus(as(otherAgent), ticket.getId(), statusJson(TicketStatus.CLOSED, version));

        assertThat(result).hasStatusOk().hasContentType(MediaType.APPLICATION_JSON);
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("CLOSED");
        assertThat(result).bodyJson().extractingPath("$.assignee").isEqualTo(assigneeView(assignee));
        assertThat(jsonLong(result, "$.version")).isEqualTo(version + 1);
        assertThat(jsonInstant(result, "$.updatedAt")).isAfter(updatedAt);
        assertStored(ticket, version + 1, TicketStatus.CLOSED, assignee);
    }

    @Test
    void agentReopensClosedTicketAndKeepsTheAssignee() throws Exception {
        Ticket ticket = ticketIn(TicketStatus.CLOSED, customer, agent);
        long version = ticket.getVersion();
        Instant updatedAt = ticket.getUpdatedAt();
        em.clear();

        MvcTestResult result = putStatus(as(otherAgent), ticket.getId(), statusJson(TicketStatus.OPEN, version));

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("OPEN");
        assertThat(result).bodyJson().extractingPath("$.assignee").isEqualTo(assigneeView(agent));
        assertThat(jsonLong(result, "$.version")).isEqualTo(version + 1);
        assertThat(jsonInstant(result, "$.updatedAt")).isAfter(updatedAt);
        assertStored(ticket, version + 1, TicketStatus.OPEN, agent);
    }

    @Test
    void statusResponseEqualsReadResponse() throws Exception {
        Ticket ticket = ticketIn(TicketStatus.ANSWERED, customer, agent);
        em.clear();

        MvcTestResult put = putStatus(as(agent), ticket.getId(), statusJson(TicketStatus.CLOSED, ticket.getVersion()));
        assertThat(put).hasStatusOk();
        // The GET must read from the database, not from the shared persistence context.
        em.flush();
        em.clear();
        MvcTestResult read = get(as(agent), ticket.getId());

        assertThat(read).hasStatusOk();
        assertThat(read.getResponse().getContentAsString()).isEqualTo(put.getResponse().getContentAsString());
    }

    @ParameterizedTest
    @CsvSource({"CLOSED, CLOSED", "OPEN, OPEN", "OPEN, ANSWERED", "OPEN, CUSTOMER_REPLIED",
            "CLOSED, ANSWERED", "CLOSED, CUSTOMER_REPLIED"})
    void manualTransitionOutsideTr07AndTr08IsConflict(TicketStatus from, TicketStatus target) throws Exception {
        Ticket ticket = ticketIn(from, customer, null);
        long version = ticket.getVersion();
        Instant updatedAt = ticket.getUpdatedAt();
        em.clear();

        MvcTestResult result = putStatus(as(agent), ticket.getId(), statusJson(target, version));

        assertTransitionConflict(result);
        assertThat(result.getResponse().getContentAsString()).doesNotContain(from.name()).doesNotContain(target.name());
        assertUnchanged(ticket, version, updatedAt, from, null);
    }

    @Test
    void staleVersionInStatusChangeIsLockingConflict() throws Exception {
        Ticket ticket = ticketIn(TicketStatus.ANSWERED, customer, agent);
        long version = ticket.getVersion();
        Instant updatedAt = ticket.getUpdatedAt();
        em.clear();

        assertLockingConflict(putStatus(as(agent), ticket.getId(), statusJson(TicketStatus.CLOSED, version - 1)));
        assertUnchanged(ticket, version, updatedAt, TicketStatus.ANSWERED, agent);
    }

    @Test
    void staleVersionIsCheckedBeforeTheTransition() throws Exception {
        Ticket ticket = ticketIn(TicketStatus.CLOSED, customer, agent);
        long version = ticket.getVersion();
        Instant updatedAt = ticket.getUpdatedAt();
        em.clear();

        assertLockingConflict(putStatus(as(agent), ticket.getId(), statusJson(TicketStatus.CLOSED, version - 1)));
        assertUnchanged(ticket, version, updatedAt, TicketStatus.CLOSED, agent);
    }

    @Test
    void missingStatusFieldsAreRejected() {
        long id = ticketIn(TicketStatus.OPEN, customer, null).getId();

        assertValidationError(putStatus(as(agent), id, "{\"version\":0}"), "status", null);
        assertValidationError(putStatus(as(agent), id, "{\"status\":\"CLOSED\"}"), "version", null);
        assertThat(putStatus(as(agent), id, "{\"status\":null,\"version\":null}")).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.errors[*].field").asArray().containsExactlyInAnyOrder("status", "version");
    }

    @Test
    void statusChangeOfMissingTicketIsNotFound() {
        assertNotFound(putStatus(as(agent), Long.MAX_VALUE, statusJson(TicketStatus.CLOSED, 0)));
        assertNotFound(putStatus(as(agent), Long.MAX_VALUE, statusJson(TicketStatus.CLOSED, 4711)));
    }

    // --- FR-12 assignment ---

    @Test
    void agentAssignsUnassignedTicket() throws Exception {
        assertAssignment(ticketIn(TicketStatus.OPEN, customer, null), otherAgent);
    }

    @Test
    void agentReassignsTicketToAnotherAgent() throws Exception {
        assertAssignment(ticketIn(TicketStatus.ANSWERED, customer, agent), otherAgent);
    }

    @Test
    void closedTicketCanBeAssignedAndStaysClosed() throws Exception {
        assertAssignment(ticketIn(TicketStatus.CLOSED, customer, agent), otherAgent);
    }

    @Test
    void assigningTheCurrentAssigneeChangesNothing() throws Exception {
        Ticket ticket = ticketIn(TicketStatus.ANSWERED, customer, agent);
        long version = ticket.getVersion();
        Instant updatedAt = ticket.getUpdatedAt();
        em.clear();

        MvcTestResult result = putAssignee(as(otherAgent), ticket.getId(), assigneeJson(agent.getId(), version));

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.assignee").isEqualTo(assigneeView(agent));
        // The response, not only the database: @PreUpdate would also change updatedAt in memory.
        assertThat(jsonLong(result, "$.version")).isEqualTo(version);
        assertThat(jsonInstant(result, "$.updatedAt")).isEqualTo(updatedAt);
        // The request succeeded, so a commit would flush; flush here to show nothing is pending.
        em.flush();
        assertUnchanged(ticket, version, updatedAt, TicketStatus.ANSWERED, agent);
    }

    @Test
    void currentAssigneeWithStaleVersionIsLockingConflict() throws Exception {
        Ticket ticket = ticketIn(TicketStatus.ANSWERED, customer, agent);
        long version = ticket.getVersion();
        Instant updatedAt = ticket.getUpdatedAt();
        em.clear();

        assertLockingConflict(putAssignee(as(agent), ticket.getId(), assigneeJson(agent.getId(), version - 1)));
        assertUnchanged(ticket, version, updatedAt, TicketStatus.ANSWERED, agent);
    }

    @Test
    void staleVersionInAssignmentIsLockingConflict() throws Exception {
        Ticket ticket = ticketIn(TicketStatus.ANSWERED, customer, agent);
        long version = ticket.getVersion();
        Instant updatedAt = ticket.getUpdatedAt();
        em.clear();

        assertLockingConflict(putAssignee(as(agent), ticket.getId(), assigneeJson(otherAgent.getId(), version - 1)));
        assertUnchanged(ticket, version, updatedAt, TicketStatus.ANSWERED, agent);
    }

    @Test
    void customerAndMissingUserAsAssigneeGetTheSameBadRequest() throws Exception {
        Ticket ticket = ticketIn(TicketStatus.OPEN, customer, null);
        long version = ticket.getVersion();
        Instant updatedAt = ticket.getUpdatedAt();
        em.clear();

        MvcTestResult customerId = putAssignee(as(agent), ticket.getId(), assigneeJson(customer.getId(), version));
        MvcTestResult missingId = putAssignee(as(agent), ticket.getId(), assigneeJson(Long.MAX_VALUE, version));
        MvcTestResult beanValidation = putAssignee(as(agent), ticket.getId(), "{\"version\":" + version + "}");

        assertThat(customerId).hasStatus(HttpStatus.BAD_REQUEST).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(customerId).bodyJson().extractingPath("$.errors")
                .isEqualTo(List.of(Map.of("field", "assigneeId", "message", "must be an existing agent")));
        String body = customerId.getResponse().getContentAsString();
        assertThat(missingId.getResponse().getContentAsString()).isEqualTo(body);
        assertThat(body).doesNotContain(String.valueOf(Long.MAX_VALUE));
        // The same shape as a bean-validation 400.
        assertValidationError(beanValidation, "assigneeId", null);
        Map<String, Object> json = JsonPath.read(body, "$");
        Map<String, Object> beanJson = JsonPath.read(beanValidation.getResponse().getContentAsString(), "$");
        assertThat(json.keySet()).isEqualTo(beanJson.keySet());
        assertThat(json).containsEntry("detail", beanJson.get("detail")).containsEntry("title", beanJson.get("title"));
        assertUnchanged(ticket, version, updatedAt, TicketStatus.OPEN, null);
    }

    @Test
    void missingAssigneeFieldsAreRejected() {
        long id = ticketIn(TicketStatus.OPEN, customer, null).getId();

        assertValidationError(putAssignee(as(agent), id, "{\"version\":0}"), "assigneeId", null);
        assertValidationError(putAssignee(as(agent), id, "{\"assigneeId\":" + agent.getId() + "}"), "version", null);
    }

    @Test
    void assigneeIsCheckedBeforeTheTicket() {
        assertValidationError(putAssignee(as(agent), Long.MAX_VALUE, assigneeJson(customer.getId(), 0)),
                "assigneeId", null);
        assertNotFound(putAssignee(as(agent), Long.MAX_VALUE, assigneeJson(agent.getId(), 0)));
        assertNotFound(putAssignee(as(agent), Long.MAX_VALUE, assigneeJson(agent.getId(), 4711)));
    }

    @Test
    void assignResponseEqualsReadResponse() throws Exception {
        Ticket ticket = ticketIn(TicketStatus.OPEN, customer, null);
        em.clear();

        MvcTestResult put = putAssignee(as(agent), ticket.getId(), assigneeJson(agent.getId(), ticket.getVersion()));
        assertThat(put).hasStatusOk();
        em.flush();
        em.clear();
        MvcTestResult read = get(as(agent), ticket.getId());

        assertThat(read).hasStatusOk();
        assertThat(read.getResponse().getContentAsString()).isEqualTo(put.getResponse().getContentAsString());
    }

    // --- FR-11 / FR-12 unreadable bodies ---

    @Test
    void unreadableStatusBodiesGetTheGenericBadRequest() throws Exception {
        Ticket ticket = ticketIn(TicketStatus.OPEN, customer, null);
        long version = ticket.getVersion();
        Instant updatedAt = ticket.getUpdatedAt();
        String[][] cases = {
                {"{\"status\":\"NOT_A_STATUS_MARKER\",\"version\":" + version + "}", "NOT_A_STATUS_MARKER"},
                {"{\"status\":\"closed\",\"version\":" + version + "}", "closed"},
                {"{\"status\":\"CLOSED\",\"version\":\"ver-marker\"}", "ver-marker"},
        };
        for (String[] c : cases) {
            assertUnreadableBody(putStatus(as(agent), ticket.getId(), c[0]), c[1]);
        }
        assertUnchanged(ticket, version, updatedAt, TicketStatus.OPEN, null);
    }

    @Test
    void unreadableAssigneeBodiesGetTheGenericBadRequest() throws Exception {
        Ticket ticket = ticketIn(TicketStatus.OPEN, customer, null);
        long version = ticket.getVersion();
        Instant updatedAt = ticket.getUpdatedAt();
        String[][] cases = {
                {"{\"assigneeId\":\"agent-marker\",\"version\":" + version + "}", "agent-marker"},
                {"{\"assigneeId\":" + agent.getId() + ",\"version\":\"ver-marker\"}", "ver-marker"},
        };
        for (String[] c : cases) {
            assertUnreadableBody(putAssignee(as(agent), ticket.getId(), c[0]), c[1]);
        }
        assertUnchanged(ticket, version, updatedAt, TicketStatus.OPEN, null);
    }

    // Jackson would truncate floats into Long fields (0.9 -> 0) unless ACCEPT_FLOAT_AS_INT is off
    // (application.yaml). Version 0 and the agent's id are valid here, so truncation would give 200.

    @Test
    void floatVersionInStatusChangeIsRejected() throws Exception {
        Ticket ticket = ticketIn(TicketStatus.OPEN, customer, null);
        assertThat(ticket.getVersion()).isZero();

        assertUnreadableBody(putStatus(as(agent), ticket.getId(), "{\"status\":\"CLOSED\",\"version\":0.9}"), "0.9");
        assertUnchanged(ticket, 0, ticket.getUpdatedAt(), TicketStatus.OPEN, null);
    }

    @Test
    void floatVersionInAssignmentIsRejected() throws Exception {
        Ticket ticket = ticketIn(TicketStatus.OPEN, customer, null);
        assertThat(ticket.getVersion()).isZero();

        assertUnreadableBody(putAssignee(as(agent), ticket.getId(),
                "{\"assigneeId\":" + agent.getId() + ",\"version\":0.9}"), "0.9");
        assertUnchanged(ticket, 0, ticket.getUpdatedAt(), TicketStatus.OPEN, null);
    }

    @Test
    void floatAssigneeIdIsRejected() throws Exception {
        Ticket ticket = ticketIn(TicketStatus.OPEN, customer, null);
        String fraction = agent.getId() + ".9";

        assertUnreadableBody(putAssignee(as(agent), ticket.getId(),
                "{\"assigneeId\":" + fraction + ",\"version\":0}"), fraction);
        assertUnchanged(ticket, 0, ticket.getUpdatedAt(), TicketStatus.OPEN, null);
    }

    @Test
    void integralFloatVersionIsRejected() throws Exception {
        // Without the setting, 1.0 would be read as 1 and give the optimistic-locking 409.
        Ticket ticket = ticketIn(TicketStatus.OPEN, customer, null);

        assertUnreadableBody(putStatus(as(agent), ticket.getId(), "{\"status\":\"CLOSED\",\"version\":1.0}"), "1.0");
        assertUnchanged(ticket, 0, ticket.getUpdatedAt(), TicketStatus.OPEN, null);
    }

    // --- inconsistent token user: 500 ---

    @Test
    void unknownUserBehindAgentTokenIsInternalError() throws Exception {
        Ticket ticket = ticketIn(TicketStatus.OPEN, customer, null);

        assertInternalError(postMessage(agentToken(Long.MAX_VALUE), ticket.getId(), messageJson("Hi")));
        assertThat(messages.findThread(ticket.getId())).hasSize(1);
    }

    @Test
    void customerBehindAgentTokenIsInternalError() throws Exception {
        Ticket ticket = ticketIn(TicketStatus.OPEN, customer, null);

        assertInternalError(postMessage(agentToken(customer.getId()), ticket.getId(), messageJson("Hi")));
        assertThat(messages.findThread(ticket.getId())).hasSize(1);
    }

    // --- security ---

    @Test
    void noTokenIsUnauthorized() {
        Ticket ticket = ticketIn(TicketStatus.OPEN, customer, null);

        assertThat(list(null, "")).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(mvc.get().uri(TICKETS + "/" + ticket.getId()).exchange()).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(postMessage(null, ticket.getId(), messageJson("Hi"))).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(putStatus(null, ticket.getId(), statusJson(TicketStatus.CLOSED, ticket.getVersion())))
                .hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(putAssignee(null, ticket.getId(), assigneeJson(agent.getId(), ticket.getVersion())))
                .hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(messages.findThread(ticket.getId())).hasSize(1);
        assertUnchanged(ticket, ticket.getVersion(), ticket.getUpdatedAt(), TicketStatus.OPEN, null);
    }

    @Test
    void customerIsForbidden() {
        Ticket ticket = ticketIn(TicketStatus.OPEN, customer, null);

        assertThat(list(as(customer), "")).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(get(as(customer), ticket.getId())).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(postMessage(as(customer), ticket.getId(), messageJson("Hi"))).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(putStatus(as(customer), ticket.getId(), statusJson(TicketStatus.CLOSED, ticket.getVersion())))
                .hasStatus(HttpStatus.FORBIDDEN);
        assertThat(putAssignee(as(customer), ticket.getId(), assigneeJson(agent.getId(), ticket.getVersion())))
                .hasStatus(HttpStatus.FORBIDDEN);
        assertThat(messages.findThread(ticket.getId())).hasSize(1);
        assertUnchanged(ticket, ticket.getVersion(), ticket.getUpdatedAt(), TicketStatus.OPEN, null);
    }

    // --- helpers ---

    /**
     * A ticket of {@code owner} in the given status, reached through the domain methods with every
     * message saved. With a {@code replier}, the ticket gets that agent's reply first, so it is
     * assigned to them (BR-09); without one, it stays unassigned, which allows only OPEN and CLOSED.
     */
    private Ticket ticketIn(TicketStatus status, User owner, User replier) {
        OpenedTicket opened = Ticket.open(owner, "Subject", TicketCategory.GENERAL, null, "First");
        Ticket ticket = tickets.save(opened.ticket());
        messages.save(opened.firstMessage());
        if (replier != null) {
            messages.save(ticket.postMessage(replier, "Agent reply"));
        }
        if (status == TicketStatus.CUSTOMER_REPLIED) {
            messages.save(ticket.postMessage(owner, "Customer reply"));
        }
        if (status == TicketStatus.CLOSED) {
            ticket.changeStatus(TicketStatus.CLOSED);
        }
        em.flush();
        assertThat(ticket.getStatus()).isEqualTo(status);
        created.add(ticket.getId());
        return ticket;
    }

    private void setLastMessageAt(Ticket ticket, Instant lastMessageAt) {
        em.createNativeQuery("update tickets set last_message_at = :lastMessageAt where id = :id")
                .setParameter("lastMessageAt", lastMessageAt)
                .setParameter("id", ticket.getId())
                .executeUpdate();
    }

    private MvcTestResult list(RequestPostProcessor auth, String query) {
        var request = mvc.get().uri(TICKETS + query);
        return auth == null ? request.exchange() : request.with(auth).exchange();
    }

    private MvcTestResult get(RequestPostProcessor auth, Object id) {
        return mvc.get().uri(TICKETS + "/" + id).with(auth).exchange();
    }

    private MvcTestResult postMessage(RequestPostProcessor auth, long ticketId, String json) {
        var request = mvc.post().uri(TICKETS + "/" + ticketId + "/messages")
                .contentType(MediaType.APPLICATION_JSON).content(json);
        return auth == null ? request.exchange() : request.with(auth).exchange();
    }

    private MvcTestResult putStatus(RequestPostProcessor auth, long ticketId, String json) {
        var request = mvc.put().uri(TICKETS + "/" + ticketId + "/status")
                .contentType(MediaType.APPLICATION_JSON).content(json);
        return auth == null ? request.exchange() : request.with(auth).exchange();
    }

    private MvcTestResult putAssignee(RequestPostProcessor auth, long ticketId, String json) {
        var request = mvc.put().uri(TICKETS + "/" + ticketId + "/assignee")
                .contentType(MediaType.APPLICATION_JSON).content(json);
        return auth == null ? request.exchange() : request.with(auth).exchange();
    }

    /**
     * Assigns {@code ticket} to {@code newAssignee} and checks that only the assignee, version and
     * updatedAt change; status, lastMessageAt and the thread stay as they were.
     */
    private void assertAssignment(Ticket ticket, User newAssignee) throws Exception {
        TicketStatus status = ticket.getStatus();
        long version = ticket.getVersion();
        Instant updatedAt = ticket.getUpdatedAt();
        Instant lastMessageAt = ticket.getLastMessageAt();
        List<Long> thread = messageIds(ticket);
        em.clear();

        MvcTestResult result = putAssignee(as(agent), ticket.getId(), assigneeJson(newAssignee.getId(), version));

        assertThat(result).hasStatusOk().hasContentType(MediaType.APPLICATION_JSON);
        assertThat(result).bodyJson().extractingPath("$.assignee").isEqualTo(assigneeView(newAssignee));
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo(status.name());
        assertThat(jsonLong(result, "$.version")).isEqualTo(version + 1);
        assertThat(jsonInstant(result, "$.updatedAt")).isAfter(updatedAt);
        assertThat(jsonInstant(result, "$.lastMessageAt")).isEqualTo(lastMessageAt);
        List<Number> responseThread = JsonPath.read(result.getResponse().getContentAsString(), "$.messages[*].id");
        assertThat(responseThread.stream().map(Number::longValue).toList()).isEqualTo(thread);
        assertStored(ticket, version + 1, status, newAssignee);
        assertThat(tickets.findById(ticket.getId()).orElseThrow().getLastMessageAt()).isEqualTo(lastMessageAt);
        assertThat(messageIds(ticket)).isEqualTo(thread);
    }

    private List<Long> messageIds(Ticket ticket) {
        return messages.findThread(ticket.getId()).stream().map(Message::getId).toList();
    }

    /** After a successful request: flushes what is pending, then reads the ticket from the database. */
    private void assertStored(Ticket ticket, long version, TicketStatus status, User assignee) {
        em.flush();
        em.clear();
        Ticket stored = tickets.findWithPeopleById(ticket.getId()).orElseThrow();
        assertThat(stored.getVersion()).isEqualTo(version);
        assertThat(stored.getStatus()).isEqualTo(status);
        if (assignee == null) {
            assertThat(stored.getAssignee()).isNull();
        } else {
            assertThat(stored.getAssignee().getId()).isEqualTo(assignee.getId());
        }
    }

    private static Map<String, Object> assigneeView(User assignee) {
        return assignee == null ? null : Map.of("id", assignee.getId().intValue(), "name", assignee.getName());
    }

    private static long jsonLong(MvcTestResult result, String path) throws UnsupportedEncodingException {
        return JsonPath.<Number>read(result.getResponse().getContentAsString(), path).longValue();
    }

    private static Instant jsonInstant(MvcTestResult result, String path) throws UnsupportedEncodingException {
        return Instant.parse(JsonPath.read(result.getResponse().getContentAsString(), path));
    }

    private static String statusJson(TicketStatus status, long version) {
        return "{\"status\":\"" + status + "\",\"version\":" + version + "}";
    }

    private static String assigneeJson(long assigneeId, long version) {
        return "{\"assigneeId\":" + assigneeId + ",\"version\":" + version + "}";
    }

    /**
     * Reads the ticket from the database. Clears first so nothing comes from the persistence
     * context, and never flushes: a failed request left the test transaction rollback-only.
     */
    private void assertUnchanged(Ticket ticket, long version, Instant updatedAt, TicketStatus status, User assignee) {
        em.clear();
        Ticket stored = tickets.findWithPeopleById(ticket.getId()).orElseThrow();
        assertThat(stored.getVersion()).isEqualTo(version);
        assertThat(stored.getUpdatedAt()).isEqualTo(updatedAt);
        assertThat(stored.getStatus()).isEqualTo(status);
        if (assignee == null) {
            assertThat(stored.getAssignee()).isNull();
        } else {
            assertThat(stored.getAssignee().getId()).isEqualTo(assignee.getId());
        }
    }

    private static void assertUnreadableBody(MvcTestResult result, String marker) throws Exception {
        assertThat(result).as(marker).hasStatus(HttpStatus.BAD_REQUEST).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).as(marker).bodyJson().extractingPath("$.detail").isEqualTo("Failed to read request");
        assertThat(result).as(marker).bodyJson().doesNotHavePath("$.errors");
        assertThat(result.getResponse().getContentAsString()).as(marker).doesNotContain(marker);
    }

    private static void assertTransitionConflict(MvcTestResult result) throws Exception {
        assertThat(result).hasStatus(HttpStatus.CONFLICT).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().extractingPath("$.detail")
                .isEqualTo("The ticket's current status does not allow this action.");
        assertThat(result.getMvcResult().getResolvedException()).isInstanceOf(InvalidTicketTransitionException.class);
        assertThat(result.getResponse().getContentAsString()).doesNotContain("version");
    }

    private static void assertLockingConflict(MvcTestResult result) throws Exception {
        assertThat(result).hasStatus(HttpStatus.CONFLICT).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().extractingPath("$.detail")
                .isEqualTo("The data was changed by another request. Reload it and try again.");
        assertThat(result.getMvcResult().getResolvedException()).isInstanceOf(ObjectOptimisticLockingFailureException.class);
        assertThat(result.getResponse().getContentAsString()).doesNotContain(Ticket.class.getName())
                .doesNotContain("version");
    }

    private static List<Long> ids(MvcTestResult result) {
        assertThat(result).hasStatusOk().hasContentType(MediaType.APPLICATION_JSON);
        try {
            List<Number> ids = JsonPath.read(result.getResponse().getContentAsString(), "$[*].id");
            return ids.stream().map(Number::longValue).toList();
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The returned ids of tickets this test created, in response order. */
    private List<Long> ownIds(MvcTestResult result) {
        return ids(result).stream().filter(created::contains).toList();
    }

    private static List<String> statuses(MvcTestResult result) throws UnsupportedEncodingException {
        return JsonPath.read(result.getResponse().getContentAsString(), "$[*].status");
    }

    private static String messageJson(String body) {
        return "{\"body\":" + quote(body) + "}";
    }

    /** A token for {@code user} with the authority matching their role. */
    private static RequestPostProcessor as(User user) {
        return jwt().jwt(token -> token.subject(user.getId().toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));
    }

    private static RequestPostProcessor agentToken(long userId) {
        return jwt().jwt(token -> token.subject(Long.toString(userId)))
                .authorities(new SimpleGrantedAuthority("ROLE_AGENT"));
    }

    private static String quote(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            if (c == '"' || c == '\\') {
                out.append('\\').append(c);
            } else if (c < 0x20) {
                out.append(String.format("\\u%04x", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.append('"').toString();
    }

    private static void assertNotFound(MvcTestResult result) {
        assertThat(result).hasStatus(HttpStatus.NOT_FOUND).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().extractingPath("$.detail").isEqualTo("Ticket not found.");
        assertThat(result).bodyJson().extractingPath("$.title").isEqualTo("Not Found");
    }

    /**
     * @param rejectedValue a distinctive part of the input that must not appear in the response, or
     *                      {@code null} when the input is only whitespace
     */
    private static void assertValidationError(MvcTestResult result, String field, String rejectedValue) {
        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().extractingPath("$.errors[*].field").asArray().containsOnly(field);
        if (rejectedValue != null) {
            String body = new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
            assertThat(body).doesNotContain(rejectedValue);
        }
    }

    private static void assertInternalError(MvcTestResult result) throws Exception {
        assertThat(result).hasStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().extractingPath("$.detail").isEqualTo("Internal server error.");
        assertThat(result.getResponse().getContentAsString())
                .doesNotContainIgnoringCase("not found")
                .doesNotContain("is not an agent")
                .doesNotContain("IllegalStateException");
    }
}
