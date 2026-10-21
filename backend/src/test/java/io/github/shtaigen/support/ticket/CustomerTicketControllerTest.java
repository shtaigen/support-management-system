package io.github.shtaigen.support.ticket;

import com.jayway.jsonpath.JsonPath;
import io.github.shtaigen.support.user.User;
import io.github.shtaigen.support.user.UserRepository;
import io.github.shtaigen.support.user.UserRole;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

// Runs against the real Postgres; each test rolls back. Emails use the ticket-test-* prefix so they
// never collide with seeded demo accounts.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@ExtendWith(OutputCaptureExtension.class)
class CustomerTicketControllerTest {

    private static final String TICKETS = "/api/customer/tickets";

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

    @BeforeEach
    void createUsers() {
        customer = users.save(new User("ticket-test-customer@example.com", "hash", "Ticket Customer", UserRole.CUSTOMER));
        otherCustomer = users.save(new User("ticket-test-other@example.com", "hash", "Other Customer", UserRole.CUSTOMER));
        agent = users.save(new User("ticket-test-agent@example.com", "hash", "Ticket Agent", UserRole.AGENT));
    }

    // --- FR-03 create ---

    @Test
    void createReturnsCreatedTicketWithFirstMessage() throws Exception {
        MvcTestResult result = create(as(customer), json("  Car insurance  ", "POLICY", " ab-12 ", "Hello\nthere"));

        assertThat(result).hasStatus(HttpStatus.CREATED).hasContentType(MediaType.APPLICATION_JSON);
        String body = result.getResponse().getContentAsString();
        long id = JsonPath.<Number>read(body, "$.id").longValue();
        assertThat(result.getResponse().getHeader(HttpHeaders.LOCATION)).isEqualTo(TICKETS + "/" + id);

        Map<String, Object> ticket = JsonPath.read(body, "$");
        assertThat(ticket).containsOnlyKeys("id", "subject", "category", "referenceNumber", "status",
                "createdAt", "lastMessageAt", "messages");
        assertThat(ticket).containsEntry("subject", "Car insurance")
                .containsEntry("category", "POLICY")
                .containsEntry("referenceNumber", "AB-12")
                .containsEntry("status", "OPEN");

        List<Map<String, Object>> thread = JsonPath.read(body, "$.messages");
        assertThat(thread).hasSize(1);
        assertThat(thread.getFirst()).containsOnlyKeys("id", "body", "createdAt", "authorRole")
                .containsEntry("body", "Hello\nthere")
                .containsEntry("authorRole", "CUSTOMER");
        assertThat(ticket.get("lastMessageAt")).isEqualTo(thread.getFirst().get("createdAt"));

        em.flush();
        em.clear();
        Ticket stored = tickets.findById(id).orElseThrow();
        assertThat(stored.getAssignee()).isNull();
        assertThat(stored.getStatus()).isEqualTo(TicketStatus.OPEN);
        assertThat(stored.getReferenceNumber()).isEqualTo("AB-12");
        assertThat(stored.getSubject()).isEqualTo("Car insurance");
        assertThat(stored.getCustomer().getId()).isEqualTo(customer.getId());
    }

    @Test
    void createResponseEqualsReadResponse() throws Exception {
        MvcTestResult created = create(as(customer), json("Subject", "CLAIM", null, "Body"));
        String body = created.getResponse().getContentAsString();

        em.flush();
        em.clear();
        MvcTestResult read = mvc.get().uri(created.getResponse().getHeader(HttpHeaders.LOCATION))
                .with(as(customer)).exchange();

        assertThat(read).hasStatusOk();
        assertThat(read.getResponse().getContentAsString()).isEqualTo(body);
    }

    @Test
    void blankReferenceNumberIsStoredAsNone() {
        MvcTestResult result = create(as(customer), json("Subject", "GENERAL", "   ", "Body"));

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(result).bodyJson().extractingPath("$.referenceNumber").isNull();
    }

    @Test
    void valuesAtTheLimitsAreAccepted() {
        MvcTestResult result = create(as(customer), json("  " + "s".repeat(Ticket.SUBJECT_MAX_LENGTH) + " ", "GENERAL",
                "R".repeat(Ticket.REFERENCE_NUMBER_MAX_LENGTH), "m".repeat(Message.BODY_MAX_LENGTH)));

        assertThat(result).hasStatus(HttpStatus.CREATED);
    }

    // --- FR-03 validation ---

    @Test
    void missingSubjectIsRejected() {
        assertValidationError(create(as(customer), json(null, "GENERAL", null, "Body")), "subject", null);
    }

    @Test
    void blankSubjectIsRejected() {
        assertValidationError(create(as(customer), json(" \t  ", "GENERAL", null, "Body")), "subject", null);
    }

    @Test
    void tooLongSubjectIsRejected() {
        String subject = "s".repeat(Ticket.SUBJECT_MAX_LENGTH + 1);

        assertValidationError(create(as(customer), json(subject, "GENERAL", null, "Body")), "subject", subject);
    }

    @Test
    void subjectWithNulIsRejected() {
        String subject = "subject-marker\u0000x";

        assertValidationError(create(as(customer), json(subject, "GENERAL", null, "Body")), "subject", "subject-marker");
    }

    @Test
    void tooLongReferenceNumberIsRejected() {
        String referenceNumber = "R".repeat(Ticket.REFERENCE_NUMBER_MAX_LENGTH + 1);

        assertValidationError(create(as(customer), json("Subject", "GENERAL", referenceNumber, "Body")),
                "referenceNumber", referenceNumber);
    }

    @Test
    void referenceNumberWithInvalidCharactersIsRejected() {
        assertValidationError(create(as(customer), json("Subject", "GENERAL", "REF_MARKER_1", "Body")),
                "referenceNumber", "REF_MARKER_1");
    }

    @Test
    void missingMessageIsRejected() {
        assertValidationError(create(as(customer), json("Subject", "GENERAL", null, null)), "message", null);
    }

    @Test
    void blankMessageIsRejected() {
        assertValidationError(create(as(customer), json("Subject", "GENERAL", null, "  \n\t ")), "message", null);
    }

    @Test
    void messageOfUnicodeWhitespaceIsRejected() {
        // @NotBlank would accept this (trim() keeps U+2003); the domain would reject it.
        assertValidationError(create(as(customer), json("Subject", "GENERAL", null, " ")), "message", null);
    }

    @Test
    void tooLongMessageIsRejected() {
        String message = "m".repeat(Message.BODY_MAX_LENGTH + 1);

        assertValidationError(create(as(customer), json("Subject", "GENERAL", null, message)), "message", message);
    }

    @Test
    void messageLengthCountsSurroundingWhitespace() {
        // The body is stored as sent, so the limit applies to the raw string, as in the domain.
        String message = " " + "m".repeat(Message.BODY_MAX_LENGTH);

        assertValidationError(create(as(customer), json("Subject", "GENERAL", null, message)), "message", message.strip());
    }

    @Test
    void messageWithNulIsRejected() {
        assertValidationError(create(as(customer), json("Subject", "GENERAL", null, "message-marker\u0000x")),
                "message", "message-marker");
    }

    @Test
    void missingCategoryIsRejected() {
        assertValidationError(create(as(customer), json("Subject", null, null, "Body")), "category", null);
    }

    @Test
    void unknownCategoryGetsTheGenericBadRequest() throws Exception {
        MvcTestResult result = create(as(customer), json("Subject", "NOT_A_CATEGORY", null, "Body"));

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().extractingPath("$.detail").isEqualTo("Failed to read request");
        assertThat(result).bodyJson().doesNotHavePath("$.errors");
        assertThat(result.getResponse().getContentAsString()).doesNotContain("NOT_A_CATEGORY");
    }

    @Test
    void malformedJsonGetsTheGenericBadRequest() {
        MvcTestResult result = create(as(customer), "{\"subject\":");

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().extractingPath("$.detail").isEqualTo("Failed to read request");
    }

    @Test
    void validationErrorKeepsItsFormat() {
        MvcTestResult result = create(as(customer), json(null, "GENERAL", null, "Body"));

        assertThat(result).bodyJson().extractingPath("$.detail").isEqualTo("Validation failed.");
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo(400);
        assertThat(result).bodyJson().extractingPath("$.errors[0].message").isEqualTo("must not be blank");
    }

    // --- FR-05 read ---

    @Test
    void ownTicketShowsThreadInOrderWithoutAssignee() throws Exception {
        OpenedTicket opened = Ticket.open(customer, "Subject", TicketCategory.GENERAL, null, "First");
        Ticket ticket = tickets.save(opened.ticket());
        messages.save(opened.firstMessage());
        messages.save(ticket.postMessage(agent, "Agent reply"));
        em.flush();
        em.clear();

        MvcTestResult result = mvc.get().uri(TICKETS + "/" + ticket.getId()).with(as(customer)).exchange();

        assertThat(result).hasStatusOk();
        String body = result.getResponse().getContentAsString();
        Map<String, Object> json = JsonPath.read(body, "$");
        // The ticket is assigned to the agent now (BR-09), but customers never see it.
        assertThat(json).doesNotContainKeys("assignee", "assigneeId", "version", "customer");
        assertThat(json).containsEntry("status", "ANSWERED");
        assertThat(JsonPath.<List<String>>read(body, "$.messages[*].body")).containsExactly("First", "Agent reply");
        assertThat(JsonPath.<List<String>>read(body, "$.messages[*].authorRole")).containsExactly("CUSTOMER", "AGENT");
        assertThat(body).doesNotContain("Ticket Agent").doesNotContain("ticket-test-agent@example.com")
                .doesNotContain("Ticket Customer");
    }

    @Test
    void foreignAndMissingTicketGetIdenticalNotFound() throws Exception {
        Ticket ticket = openTicket(customer);
        String path = TICKETS + "/" + ticket.getId();

        MvcTestResult foreign = mvc.get().uri(path).with(as(otherCustomer)).exchange();

        em.createQuery("delete from Message m where m.ticket.id = :id").setParameter("id", ticket.getId()).executeUpdate();
        em.createQuery("delete from Ticket t where t.id = :id").setParameter("id", ticket.getId()).executeUpdate();
        em.clear();
        MvcTestResult missing = mvc.get().uri(path).with(as(customer)).exchange();

        assertThat(foreign).hasStatus(HttpStatus.NOT_FOUND).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(foreign).bodyJson().extractingPath("$.detail").isEqualTo("Ticket not found.");
        assertThat(foreign).bodyJson().extractingPath("$.title").isEqualTo("Not Found");
        assertThat(missing.getResponse().getContentAsString()).isEqualTo(foreign.getResponse().getContentAsString());
    }

    @Test
    void nonNumericOrOverflowingIdIsRejectedWithoutEchoingIt() throws Exception {
        for (String id : new String[] {"abc-marker", "99999999999999999999"}) {
            MvcTestResult result = mvc.get().uri(TICKETS + "/" + id).with(as(customer)).exchange();

            assertThat(result).hasStatus(HttpStatus.BAD_REQUEST).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
            assertThat(result).bodyJson().extractingPath("$.errors[*].field").asArray().containsOnly("id");
            // RFC 9457 instance is the request path, which contains the id the client sent; nothing
            // else may contain it.
            Map<String, Object> problem = new LinkedHashMap<>(JsonPath.read(result.getResponse().getContentAsString(), "$"));
            assertThat(problem.remove("instance")).isEqualTo(TICKETS + "/" + id);
            assertThat(problem.toString()).doesNotContain(id);
        }
    }

    // --- FR-04 list ---

    @Test
    void listShowsOnlyOwnTickets() {
        Ticket open = ticketIn(TicketStatus.OPEN, customer);
        Ticket closed = ticketIn(TicketStatus.CLOSED, customer);
        ticketIn(TicketStatus.OPEN, otherCustomer);

        MvcTestResult result = list(as(customer), "");

        assertThat(result).hasStatusOk().hasContentType(MediaType.APPLICATION_JSON);
        assertThat(ids(result)).containsExactlyInAnyOrder(open.getId(), closed.getId());
    }

    @Test
    void listIsOrderedByLastMessageAtThenId() {
        // newest is created first (lowest id); older and olderTie share a timestamp, so id decides.
        Ticket newest = ticketIn(TicketStatus.OPEN, customer);
        Ticket older = ticketIn(TicketStatus.OPEN, customer);
        Ticket olderTie = ticketIn(TicketStatus.OPEN, customer);
        Instant tie = Instant.parse("2026-01-01T10:00:00Z");
        setLastMessageAt(newest, tie.plusSeconds(3600));
        setLastMessageAt(older, tie);
        setLastMessageAt(olderTie, tie);
        em.clear();

        assertThat(ids(list(as(customer), ""))).containsExactly(newest.getId(), olderTie.getId(), older.getId());
    }

    @Test
    void listFiltersByStatus() {
        Ticket open = ticketIn(TicketStatus.OPEN, customer);
        Ticket answered = ticketIn(TicketStatus.ANSWERED, customer);
        Ticket replied = ticketIn(TicketStatus.CUSTOMER_REPLIED, customer);
        Ticket closed = ticketIn(TicketStatus.CLOSED, customer);
        em.clear();

        assertThat(ids(list(as(customer), "?status=ANSWERED"))).containsExactly(answered.getId());
        assertThat(ids(list(as(customer), "?status=OPEN&status=CLOSED")))
                .containsExactlyInAnyOrder(open.getId(), closed.getId());
        assertThat(ids(list(as(customer), "")))
                .containsExactlyInAnyOrder(open.getId(), answered.getId(), replied.getId(), closed.getId());
    }

    @Test
    void emptyStatusValuesAreIgnored() {
        Ticket open = ticketIn(TicketStatus.OPEN, customer);
        Ticket closed = ticketIn(TicketStatus.CLOSED, customer);
        em.clear();

        assertThat(ids(list(as(customer), "?status="))).containsExactlyInAnyOrder(open.getId(), closed.getId());
        assertThat(ids(list(as(customer), "?status=CLOSED,"))).containsExactly(closed.getId());
        assertThat(ids(list(as(customer), "?status=&status=CLOSED"))).containsExactly(closed.getId());
    }

    @Test
    void commaSeparatedStatusesAreAccepted() {
        Ticket open = ticketIn(TicketStatus.OPEN, customer);
        Ticket closed = ticketIn(TicketStatus.CLOSED, customer);
        ticketIn(TicketStatus.ANSWERED, customer);
        em.clear();

        assertThat(ids(list(as(customer), "?status=OPEN,CLOSED"))).containsExactlyInAnyOrder(open.getId(), closed.getId());
    }

    @Test
    void unknownStatusIsRejectedWithoutEchoingIt() throws Exception {
        for (String query : new String[] {"?status=NOT_A_STATUS_MARKER", "?status=OPEN,BOGUS_MARKER",
                "?status=OPEN&status=BOGUS_MARKER"}) {
            MvcTestResult result = list(as(customer), query);

            assertThat(result).hasStatus(HttpStatus.BAD_REQUEST).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
            assertThat(result).bodyJson().extractingPath("$.errors[*].field").asArray().containsOnly("status");
            assertThat(result.getResponse().getContentAsString())
                    .doesNotContain("NOT_A_STATUS_MARKER").doesNotContain("BOGUS_MARKER");
        }
    }

    @Test
    void lowercaseStatusIsRejected() {
        // Spring MVC converts enums with the case-sensitive core converter.
        MvcTestResult result = list(as(customer), "?status=open");

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.errors[*].field").asArray().containsOnly("status");
    }

    @Test
    void listItemsShowNoAssigneeOrVersion() throws Exception {
        ticketIn(TicketStatus.ANSWERED, customer);
        em.clear();

        String body = list(as(customer), "").getResponse().getContentAsString();

        List<Map<String, Object>> items = JsonPath.read(body, "$");
        assertThat(items).hasSize(1);
        assertThat(items.getFirst()).containsOnlyKeys("id", "subject", "category", "referenceNumber", "status",
                "createdAt", "lastMessageAt");
        assertThat(items.getFirst()).containsEntry("status", "ANSWERED");
        assertThat(body).doesNotContain("Ticket Agent").doesNotContain("ticket-test-agent@example.com");
    }

    // --- FR-06 post a message ---

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "ANSWERED, CUSTOMER_REPLIED",          // TR-03
            "OPEN, OPEN",                          // TR-04
            "CUSTOMER_REPLIED, CUSTOMER_REPLIED",  // TR-05
            "CLOSED, CUSTOMER_REPLIED"             // TR-06
    })
    void customerMessageSetsStatus(TicketStatus from, TicketStatus to) {
        Ticket ticket = ticketIn(from, customer);
        em.clear();

        MvcTestResult result = postMessage(as(customer), ticket.getId(), messageJson("Hello"));

        assertThat(result).hasStatusOk().hasContentType(MediaType.APPLICATION_JSON);
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo(to.name());
    }

    @Test
    void reopeningAClosedTicketKeepsTheAssignee() {
        Ticket ticket = ticketIn(TicketStatus.CLOSED, customer);
        long version = ticket.getVersion();
        Instant updatedAt = ticket.getUpdatedAt();
        em.clear();

        assertThat(postMessage(as(customer), ticket.getId(), messageJson("Reopen please"))).hasStatusOk();
        em.flush();
        em.clear();

        Ticket stored = tickets.findById(ticket.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(TicketStatus.CUSTOMER_REPLIED);
        assertThat(stored.getAssignee().getId()).isEqualTo(agent.getId());
        assertThat(stored.getVersion()).isEqualTo(version + 1);
        assertThat(stored.getUpdatedAt()).isAfter(updatedAt);
    }

    @Test
    void newMessageIsLastInThreadAndSetsLastMessageAt() throws Exception {
        Ticket ticket = ticketIn(TicketStatus.ANSWERED, customer);
        em.clear();

        MvcTestResult result = postMessage(as(customer), ticket.getId(), messageJson("  New\nmessage  "));

        String body = result.getResponse().getContentAsString();
        List<Map<String, Object>> thread = JsonPath.read(body, "$.messages");
        assertThat(thread).hasSize(3);
        assertThat(thread.getLast()).containsEntry("body", "  New\nmessage  ").containsEntry("authorRole", "CUSTOMER");
        assertThat(JsonPath.<String>read(body, "$.lastMessageAt")).isEqualTo(thread.getLast().get("createdAt"));
    }

    @Test
    void postResponseEqualsReadResponse() throws Exception {
        Ticket ticket = ticketIn(TicketStatus.ANSWERED, customer);
        em.clear();

        MvcTestResult posted = postMessage(as(customer), ticket.getId(), messageJson("Body"));
        assertThat(posted).hasStatusOk();
        // The GET must read from the database, not from the shared persistence context.
        em.flush();
        em.clear();
        MvcTestResult read = mvc.get().uri(TICKETS + "/" + ticket.getId()).with(as(customer)).exchange();

        assertThat(read).hasStatusOk();
        assertThat(read.getResponse().getContentAsString()).isEqualTo(posted.getResponse().getContentAsString());
    }

    @Test
    void messageBodyAtTheLimitIsAccepted() {
        Ticket ticket = openTicket(customer);

        assertThat(postMessage(as(customer), ticket.getId(), messageJson("m".repeat(Message.BODY_MAX_LENGTH))))
                .hasStatusOk();
    }

    @Test
    void invalidMessageBodiesAreRejected() {
        long id = openTicket(customer).getId();
        String tooLong = "m".repeat(Message.BODY_MAX_LENGTH + 1);
        String paddedTooLong = " " + "m".repeat(Message.BODY_MAX_LENGTH);

        assertValidationError(postMessage(as(customer), id, "{}"), "body", null);
        assertValidationError(postMessage(as(customer), id, messageJson(null)), "body", null);
        assertValidationError(postMessage(as(customer), id, messageJson("  \n\t ")), "body", null);
        assertValidationError(postMessage(as(customer), id, messageJson("body-marker\u0000x")), "body", "body-marker");
        assertValidationError(postMessage(as(customer), id, messageJson(tooLong)), "body", tooLong);
        assertValidationError(postMessage(as(customer), id, messageJson(paddedTooLong)), "body", paddedTooLong.strip());
        assertThat(messages.findThread(id)).hasSize(1);
    }

    @Test
    void messageToForeignAndMissingTicketGetsIdenticalNotFound() throws Exception {
        Ticket ticket = openTicket(customer);

        MvcTestResult foreign = postMessage(as(otherCustomer), ticket.getId(), messageJson("Hi"));
        assertThat(messages.findThread(ticket.getId())).hasSize(1);

        em.createQuery("delete from Message m where m.ticket.id = :id").setParameter("id", ticket.getId()).executeUpdate();
        em.createQuery("delete from Ticket t where t.id = :id").setParameter("id", ticket.getId()).executeUpdate();
        em.clear();
        MvcTestResult missing = postMessage(as(customer), ticket.getId(), messageJson("Hi"));

        assertThat(foreign).hasStatus(HttpStatus.NOT_FOUND).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(foreign).bodyJson().extractingPath("$.detail").isEqualTo("Ticket not found.");
        assertThat(missing.getResponse().getContentAsString()).isEqualTo(foreign.getResponse().getContentAsString());
    }

    // --- security ---

    @Test
    void noTokenIsUnauthorized() {
        assertThat(create(null, json("Subject", "GENERAL", null, "Body"))).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(mvc.get().uri(TICKETS + "/1").exchange()).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(list(null, "")).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(postMessage(null, 1L, messageJson("Hi"))).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void agentIsForbidden() {
        Ticket ticket = openTicket(customer);

        assertThat(create(as(agent), json("Subject", "GENERAL", null, "Body"))).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(mvc.get().uri(TICKETS + "/" + ticket.getId()).with(as(agent)).exchange())
                .hasStatus(HttpStatus.FORBIDDEN);
        assertThat(list(as(agent), "")).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(postMessage(as(agent), ticket.getId(), messageJson("Hi"))).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(messages.findThread(ticket.getId())).hasSize(1);
    }

    // --- inconsistent token user: 500 ---

    @Test
    void unknownUserBehindValidTokenIsInternalError(CapturedOutput output) throws Exception {
        long before = tickets.count();

        MvcTestResult result = create(customerToken(Long.MAX_VALUE), json("Subject", "GENERAL", null, "Body"));

        assertInternalError(result);
        assertThat(tickets.count()).isEqualTo(before);
        assertThat(output.getAll()).contains("Unhandled exception").contains("IllegalStateException");
    }

    @Test
    void nonCustomerUserBehindCustomerTokenIsInternalError() throws Exception {
        long before = tickets.count();

        MvcTestResult result = create(customerToken(agent.getId()), json("Subject", "GENERAL", null, "Body"));

        assertInternalError(result);
        assertThat(tickets.count()).isEqualTo(before);
    }

    // --- helpers ---

    private Ticket openTicket(User owner) {
        OpenedTicket opened = Ticket.open(owner, "Subject", TicketCategory.GENERAL, null, "First");
        Ticket ticket = tickets.save(opened.ticket());
        messages.save(opened.firstMessage());
        em.flush();
        return ticket;
    }

    /**
     * A ticket of {@code owner} in the given status, reached through the domain methods with every
     * message saved. ANSWERED, CUSTOMER_REPLIED and CLOSED are assigned to {@link #agent} (BR-09).
     */
    private Ticket ticketIn(TicketStatus status, User owner) {
        OpenedTicket opened = Ticket.open(owner, "Subject", TicketCategory.GENERAL, null, "First");
        Ticket ticket = tickets.save(opened.ticket());
        messages.save(opened.firstMessage());
        if (status != TicketStatus.OPEN) {
            messages.save(ticket.postMessage(agent, "Agent reply"));
        }
        if (status == TicketStatus.CUSTOMER_REPLIED) {
            messages.save(ticket.postMessage(owner, "Customer reply"));
        }
        if (status == TicketStatus.CLOSED) {
            ticket.changeStatus(TicketStatus.CLOSED);
        }
        em.flush();
        return ticket;
    }

    private void setLastMessageAt(Ticket ticket, Instant lastMessageAt) {
        em.createNativeQuery("update tickets set last_message_at = :lastMessageAt where id = :id")
                .setParameter("lastMessageAt", lastMessageAt)
                .setParameter("id", ticket.getId())
                .executeUpdate();
    }

    private MvcTestResult create(RequestPostProcessor auth, String json) {
        var request = mvc.post().uri(TICKETS).contentType(MediaType.APPLICATION_JSON).content(json);
        return auth == null ? request.exchange() : request.with(auth).exchange();
    }

    private MvcTestResult list(RequestPostProcessor auth, String query) {
        var request = mvc.get().uri(TICKETS + query);
        return auth == null ? request.exchange() : request.with(auth).exchange();
    }

    private MvcTestResult postMessage(RequestPostProcessor auth, long ticketId, String json) {
        var request = mvc.post().uri(TICKETS + "/" + ticketId + "/messages")
                .contentType(MediaType.APPLICATION_JSON).content(json);
        return auth == null ? request.exchange() : request.with(auth).exchange();
    }

    private static List<Long> ids(MvcTestResult result) {
        assertThat(result).hasStatusOk();
        try {
            List<Number> ids = JsonPath.read(result.getResponse().getContentAsString(), "$[*].id");
            return ids.stream().map(Number::longValue).toList();
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String messageJson(String body) {
        return "{\"body\":" + quote(body) + "}";
    }

    /** A token for {@code user} with the authority matching their role. */
    private static RequestPostProcessor as(User user) {
        return jwt().jwt(token -> token.subject(user.getId().toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));
    }

    private static RequestPostProcessor customerToken(long userId) {
        return jwt().jwt(token -> token.subject(Long.toString(userId)))
                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    private static String json(String subject, String category, String referenceNumber, String message) {
        return "{\"subject\":" + quote(subject) + ",\"category\":" + quote(category)
                + ",\"referenceNumber\":" + quote(referenceNumber) + ",\"message\":" + quote(message) + "}";
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
                .doesNotContain("is not a customer")
                .doesNotContain("IllegalStateException")
                .doesNotContain("User ");
    }
}
