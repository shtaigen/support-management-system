package io.github.shtaigen.support.auth;

import com.jayway.jsonpath.JsonPath;
import io.github.shtaigen.support.user.User;
import io.github.shtaigen.support.user.UserRepository;
import io.github.shtaigen.support.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

// Runs against the real Postgres; each test rolls back. Emails use the security-test-* prefix so
// they never collide with seeded demo accounts. Paths under the zones need no controllers: a 404
// proves the request got past security.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@ExtendWith(OutputCaptureExtension.class)
class SecurityIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");
    private static final String PASSWORD = "correct-horse-battery";
    private static final String CUSTOMER_PATH = "/api/customer/x";
    private static final String AGENT_PATH = "/api/agent/x";

    @TestConfiguration
    static class FixedClockConfig {

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private JwtProperties jwtProperties;

    @Autowired
    private TokenIssuer tokenIssuer;

    private User customer;
    private User agent;

    @BeforeEach
    void createUsers() {
        String hash = passwordEncoder.encode(PASSWORD);
        customer = users.save(new User("security-test-customer@example.com", hash, "Sec Customer", UserRole.CUSTOMER));
        agent = users.save(new User("security-test-agent@example.com", hash, "Sec Agent", UserRole.AGENT));
    }

    // --- 401: no or rejected token ---

    @Test
    void noTokenOnEitherZoneIsUnauthorizedWithBareBearerChallenge() {
        for (String path : new String[] {CUSTOMER_PATH, AGENT_PATH}) {
            MvcTestResult result = mvc.get().uri(path).exchange();

            assertUnauthorized(result);
            assertThat(result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer");
            assertThat(result).bodyJson().extractingPath("$.instance").isEqualTo(path);
        }
    }

    @Test
    void expiredTokenIsRejected() {
        String token = mint(claims -> claims.expiresAt(NOW.minusSeconds(61)).issuedAt(NOW.minusSeconds(3661)));

        assertInvalidToken(get(AGENT_PATH, token));
    }

    @Test
    void tokenExpiredWithinDefaultClockSkewIsAccepted() {
        String token = mint(claims -> claims.expiresAt(NOW.minusSeconds(30)).issuedAt(NOW.minusSeconds(3630)));

        assertThat(get(AGENT_PATH, token)).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void tokenWithWrongSignatureIsRejected() {
        assertInvalidToken(get(AGENT_PATH, forgedToken()));
    }

    @Test
    void tokenWithWrongIssuerIsRejected() {
        assertInvalidToken(get(AGENT_PATH, mint(claims -> claims.issuer("someone-else"))));
    }

    @Test
    void malformedTokenIsRejected() {
        assertInvalidToken(get(AGENT_PATH, "not-a-jwt"));
    }

    @Test
    void unsignedTokenIsRejected() {
        Base64.Encoder base64 = Base64.getUrlEncoder().withoutPadding();
        String header = base64.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        String payload = base64.encodeToString(("{\"iss\":\"" + jwtProperties.issuer() + "\",\"sub\":\"1\","
                + "\"role\":\"AGENT\",\"exp\":" + NOW.plusSeconds(3600).getEpochSecond() + "}")
                .getBytes(StandardCharsets.UTF_8));

        assertInvalidToken(get(AGENT_PATH, header + "." + payload + "."));
    }

    @Test
    void tokenWithoutExpiryIsRejected() {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwtProperties.issuer())
                .subject(agent.getId().toString())
                .claim(TokenIssuer.ROLE_CLAIM, "AGENT")
                .issuedAt(NOW)
                .build();

        assertInvalidToken(get(AGENT_PATH, encode(jwtEncoder, claims)));
    }

    @Test
    void rejectionsAreIndistinguishable() throws Exception {
        String missing = mvc.get().uri(AGENT_PATH).exchange().getResponse().getContentAsString();
        MvcTestResult[] rejected = {
                get(AGENT_PATH, mint(claims -> claims.expiresAt(NOW.minusSeconds(120)).issuedAt(NOW.minusSeconds(3720)))),
                get(AGENT_PATH, forgedToken()),
                get(AGENT_PATH, mint(claims -> claims.issuer("someone-else"))),
                get(AGENT_PATH, "not-a-jwt"),
        };

        for (MvcTestResult result : rejected) {
            String challenge = result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE);
            assertThat(challenge).isEqualTo("Bearer error=\"invalid_token\"");
            assertThat(result.getResponse().getContentAsString()).isEqualTo(missing);
        }
        assertThat(missing).doesNotContainIgnoringCase("expired")
                .doesNotContainIgnoringCase("signature")
                .doesNotContainIgnoringCase("issuer");
    }

    @Test
    void rejectedTokensAreNotLogged(CapturedOutput output) {
        String forged = forgedToken();
        String wrongIssuer = mint(claims -> claims.issuer("someone-else"));

        get(AGENT_PATH, forged);
        get(AGENT_PATH, wrongIssuer);

        assertThat(output.getAll()).doesNotContain(forged).doesNotContain(wrongIssuer);
    }

    // --- 403: authenticated, wrong or no role ---

    @Test
    void tokenWithoutRoleIsForbiddenEverywhere() {
        String token = mint(claims -> claims.claims(all -> all.remove(TokenIssuer.ROLE_CLAIM)));

        assertForbidden(get(CUSTOMER_PATH, token));
        assertForbidden(get(AGENT_PATH, token));
    }

    @Test
    void tokenWithUnknownRoleIsForbiddenEverywhere() {
        String token = mint(claims -> claims.claim(TokenIssuer.ROLE_CLAIM, "ADMIN"));

        assertForbidden(get(CUSTOMER_PATH, token));
        assertForbidden(get(AGENT_PATH, token));
    }

    @Test
    void customerIsForbiddenInAgentZone() {
        assertForbidden(mvc.get().uri(AGENT_PATH).with(jwt().authorities(role("CUSTOMER"))).exchange());
        assertForbidden(get(AGENT_PATH, tokenIssuer.issue(customer).value()));
    }

    @Test
    void agentIsForbiddenInCustomerZone() {
        assertForbidden(mvc.get().uri(CUSTOMER_PATH).with(jwt().authorities(role("AGENT"))).exchange());
        assertForbidden(get(CUSTOMER_PATH, tokenIssuer.issue(agent).value()));
    }

    @Test
    void ownZonePassesSecurity() {
        assertNotFound(mvc.get().uri(CUSTOMER_PATH).with(jwt().authorities(role("CUSTOMER"))).exchange());
        assertNotFound(mvc.get().uri(AGENT_PATH).with(jwt().authorities(role("AGENT"))).exchange());
        assertNotFound(get(CUSTOMER_PATH, tokenIssuer.issue(customer).value()));
        assertNotFound(get(AGENT_PATH, tokenIssuer.issue(agent).value()));
    }

    @Test
    void pathsOutsideZonesAreDenied() {
        String token = tokenIssuer.issue(agent).value();
        for (String path : new String[] {"/api/tickets", "/foo", "/actuator/env", "/actuator/health/db"}) {
            assertForbidden(get(path, token));
            assertUnauthorized(mvc.get().uri(path).exchange());
        }
    }

    @Test
    void loginAcceptsOnlyPost() {
        assertUnauthorized(mvc.get().uri("/api/auth/login").exchange());
        assertForbidden(get("/api/auth/login", tokenIssuer.issue(agent).value()));
    }

    // --- end to end and public endpoints ---

    @Test
    void customerTokenFromLoginWorksOnlyInCustomerZone() throws Exception {
        String token = loginToken("security-test-customer@example.com");

        assertNotFound(get(CUSTOMER_PATH, token));
        assertForbidden(get(AGENT_PATH, token));
    }

    @Test
    void agentTokenFromLoginWorksOnlyInAgentZone() throws Exception {
        String token = loginToken("security-test-agent@example.com");

        assertNotFound(get(AGENT_PATH, token));
        assertForbidden(get(CUSTOMER_PATH, token));
    }

    @Test
    void healthNeedsNoToken() {
        assertThat(mvc.get().uri("/actuator/health")).hasStatusOk();
    }

    @Test
    void noResponseCreatesSessionOrCookie() {
        String token = tokenIssuer.issue(customer).value();
        MvcTestResult[] results = {
                login("security-test-customer@example.com"),
                mvc.get().uri(CUSTOMER_PATH).exchange(),
                get(AGENT_PATH, token),
                get(CUSTOMER_PATH, token),
                mvc.get().uri("/actuator/health").exchange(),
        };

        for (MvcTestResult result : results) {
            assertThat(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
            assertThat(result.getRequest().getSession(false)).isNull();
        }
    }

    // --- helpers ---

    private MvcTestResult get(String path, String token) {
        return mvc.get().uri(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange();
    }

    private MvcTestResult login(String email) {
        String json = "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}";
        return mvc.post().uri("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json).exchange();
    }

    private String loginToken(String email) throws Exception {
        MvcTestResult result = login(email);
        assertThat(result).hasStatusOk();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    /** A valid agent token by default; {@code customizer} breaks or changes one claim. */
    private String mint(Consumer<JwtClaimsSet.Builder> customizer) {
        JwtClaimsSet.Builder claims = validAgentClaims();
        customizer.accept(claims);
        return encode(jwtEncoder, claims.build());
    }

    private String forgedToken() {
        byte[] otherKey = new byte[32];
        new SecureRandom().nextBytes(otherKey);
        JwtEncoder forger = NimbusJwtEncoder.withSecretKey(new SecretKeySpec(otherKey, "HmacSHA256")).build();
        return encode(forger, validAgentClaims().build());
    }

    private JwtClaimsSet.Builder validAgentClaims() {
        return JwtClaimsSet.builder()
                .issuer(jwtProperties.issuer())
                .subject(agent.getId().toString())
                .claim(TokenIssuer.ROLE_CLAIM, "AGENT")
                .issuedAt(NOW)
                .expiresAt(NOW.plusSeconds(3600));
    }

    private static String encode(JwtEncoder encoder, JwtClaimsSet claims) {
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private static SimpleGrantedAuthority role(String role) {
        return new SimpleGrantedAuthority("ROLE_" + role);
    }

    private static void assertUnauthorized(MvcTestResult result) {
        assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Bearer");
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo(401);
        assertThat(result).bodyJson().extractingPath("$.title").isEqualTo("Unauthorized");
    }

    private static void assertInvalidToken(MvcTestResult result) {
        assertUnauthorized(result);
        assertThat(result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE))
                .isEqualTo("Bearer error=\"invalid_token\"");
    }

    private static void assertForbidden(MvcTestResult result) {
        assertThat(result).hasStatus(HttpStatus.FORBIDDEN)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo(403);
        assertThat(result).bodyJson().extractingPath("$.title").isEqualTo("Forbidden");
    }

    private static void assertNotFound(MvcTestResult result) {
        assertThat(result).hasStatus(HttpStatus.NOT_FOUND)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
    }
}
