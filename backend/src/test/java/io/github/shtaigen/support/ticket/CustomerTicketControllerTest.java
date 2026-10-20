package io.github.shtaigen.support.ticket;

import com.jayway.jsonpath.JsonPath;
import io.github.shtaigen.support.user.User;
import io.github.shtaigen.support.user.UserRepository;
import io.github.shtaigen.support.user.UserRole;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

import java.nio.charset.StandardCharsets;
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

    // --- security ---

    @Test
    void noTokenIsUnauthorized() {
        assertThat(create(null, json("Subject", "GENERAL", null, "Body"))).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(mvc.get().uri(TICKETS + "/1").exchange()).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void agentIsForbidden() {
        Ticket ticket = openTicket(customer);

        assertThat(create(as(agent), json("Subject", "GENERAL", null, "Body"))).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(mvc.get().uri(TICKETS + "/" + ticket.getId()).with(as(agent)).exchange())
                .hasStatus(HttpStatus.FORBIDDEN);
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

    private MvcTestResult create(RequestPostProcessor auth, String json) {
        var request = mvc.post().uri(TICKETS).contentType(MediaType.APPLICATION_JSON).content(json);
        return auth == null ? request.exchange() : request.with(auth).exchange();
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
