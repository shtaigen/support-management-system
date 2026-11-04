package io.github.shtaigen.support.user;

import com.jayway.jsonpath.JsonPath;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

// Runs against the real Postgres; each test rolls back. Other committed users (for example demo
// accounts) may exist, so assertions look only at the users this test created. The order depends
// on the database collation, so the response as a whole is never compared with an order computed
// in Java (ADR-016).
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AgentControllerTest {

    private static final String AGENTS = "/api/agent/agents";

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private UserRepository users;

    private User caller;

    /** Agents created by the test itself, not counting the caller. */
    private final List<Long> created = new ArrayList<>();

    @BeforeEach
    void createCaller() {
        caller = users.save(new User("user-test-agents-caller@example.com", "hash", "Caller Agent", UserRole.AGENT));
    }

    @Test
    void callerAndOtherAgentsAreListed() {
        User other = agent("other", "Other Agent");

        assertThat(ids(list(as(caller)))).contains(caller.getId(), other.getId());
    }

    @Test
    void customerIsNotListed() {
        User customer = customer("listed", "Listed Customer");

        assertThat(ids(list(as(caller)))).doesNotContain(customer.getId());
    }

    @Test
    void itemsHaveExactlyIdAndName() {
        agent("fields", "Fields Agent");

        MvcTestResult result = list(as(caller));

        assertThat(ownItems(result)).hasSize(1);
        for (Map<String, Object> item : items(result)) {
            assertThat(item).containsOnlyKeys("id", "name");
        }
    }

    @Test
    void agentsAreOrderedByName() {
        // Created in reverse name order, so id order differs from name order. The names differ only
        // in one uppercase ASCII letter, so the expected order is the same in any collation.
        User c = agent("order-c", "Order Agent C");
        User b = agent("order-b", "Order Agent B");
        User a = agent("order-a", "Order Agent A");

        assertThat(ownIds(list(as(caller)))).containsExactly(a.getId(), b.getId(), c.getId());
    }

    @Test
    void agentsWithTheSameNameAreOrderedById() {
        // Six agents tie, so only id decides their order (NFR-05). Removing the id from the order
        // did not fail this test in step 8a: Postgres returned the tied rows in id order anyway,
        // which it does not guarantee (ADR-016).
        List<Long> expected = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            expected.add(agent("tied-" + i, "Tied Agent").getId());
        }

        assertThat(ownIds(list(as(caller)))).containsExactlyElementsOf(expected);
    }

    @Test
    void noTokenIsUnauthorized() {
        assertThat(mvc.get().uri(AGENTS).exchange()).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void customerIsForbidden() {
        User customer = customer("forbidden", "Forbidden Customer");

        assertThat(mvc.get().uri(AGENTS).with(as(customer)).exchange()).hasStatus(HttpStatus.FORBIDDEN);
    }

    // --- helpers ---

    private User agent(String key, String name) {
        User agent = users.save(new User("user-test-agents-" + key + "@example.com", "hash", name, UserRole.AGENT));
        created.add(agent.getId());
        return agent;
    }

    private User customer(String key, String name) {
        return users.save(new User("user-test-agents-" + key + "@example.com", "hash", name, UserRole.CUSTOMER));
    }

    /** The agent list, checked to be a successful JSON response. */
    private MvcTestResult list(RequestPostProcessor auth) {
        MvcTestResult result = mvc.get().uri(AGENTS).with(auth).exchange();
        assertThat(result).hasStatusOk().hasContentType(MediaType.APPLICATION_JSON);
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

    /** The items of agents this test created, in response order. */
    private List<Map<String, Object>> ownItems(MvcTestResult result) {
        return items(result).stream().filter(item -> created.contains(((Number) item.get("id")).longValue())).toList();
    }

    private List<Long> ownIds(MvcTestResult result) {
        return ownItems(result).stream().map(item -> ((Number) item.get("id")).longValue()).toList();
    }

    /** A token for {@code user} with the authority matching their role. */
    private static RequestPostProcessor as(User user) {
        return jwt().jwt(token -> token.subject(user.getId().toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));
    }
}
