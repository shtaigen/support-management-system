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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

// Runs against the real Postgres; each test rolls back. Emails use the auth-test-* prefix so they
// never collide with seeded demo accounts.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@ExtendWith(OutputCaptureExtension.class)
class AuthControllerTest {

    // Not on a whole second, to prove that iat, exp and expiresAt are truncated.
    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00.700Z");
    private static final Instant ISSUED_AT = Instant.parse("2026-01-01T10:00:00Z");
    private static final Instant EXPIRES_AT = Instant.parse("2026-01-01T11:00:00Z");

    private static final String EMAIL = "auth-test-login@example.com";
    private static final String PASSWORD = "correct-horse-battery";

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
    private JwtProperties jwtProperties;

    private User user;

    @BeforeEach
    void createUser() {
        user = users.save(new User(EMAIL, passwordEncoder.encode(PASSWORD), "Auth Tester", UserRole.AGENT));
    }

    @Test
    void validCredentialsReturnVerifiableToken() throws Exception {
        MvcTestResult result = login(EMAIL, PASSWORD);

        assertThat(result).hasStatusOk();
        String body = result.getResponse().getContentAsString();
        Jwt jwt = decode(JsonPath.read(body, "$.accessToken"));

        assertThat(jwt.getHeaders()).containsEntry("alg", "HS256");
        assertThat(jwt.getSubject()).isEqualTo(user.getId().toString());
        assertThat(jwt.getClaimAsString("role")).isEqualTo("AGENT");
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("support-backend");
        assertThat(jwt.getIssuedAt()).isEqualTo(ISSUED_AT);
        assertThat(jwt.getExpiresAt()).isEqualTo(EXPIRES_AT);
        assertThat(jwt.getClaims()).containsOnlyKeys("iss", "sub", "role", "iat", "exp");

        assertThat(Instant.parse(JsonPath.read(body, "$.expiresAt"))).isEqualTo(jwt.getExpiresAt());
        assertThat(JsonPath.<Integer>read(body, "$.user.id").longValue()).isEqualTo(user.getId());
        assertThat(JsonPath.<String>read(body, "$.user.name")).isEqualTo("Auth Tester");
        assertThat(JsonPath.<String>read(body, "$.user.role")).isEqualTo("AGENT");
        assertThat(body).doesNotContain(user.getPasswordHash()).doesNotContain(EMAIL);
    }

    @Test
    void emailLookupIgnoresCaseAndSurroundingWhitespace() {
        assertThat(login("  Auth-Test-Login@Example.COM ", PASSWORD)).hasStatusOk();
    }

    @Test
    void wrongPasswordAndUnknownEmailGetIdenticalUnauthorizedResponse() throws Exception {
        MvcTestResult wrongPassword = login(EMAIL, "wrong-password");
        MvcTestResult unknownEmail = login("auth-test-nobody@example.com", PASSWORD);

        assertThat(wrongPassword).hasStatus(HttpStatus.UNAUTHORIZED)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(unknownEmail).hasStatus(HttpStatus.UNAUTHORIZED)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(unknownEmail.getResponse().getContentAsString())
                .isEqualTo(wrongPassword.getResponse().getContentAsString());
        assertThat(wrongPassword).bodyJson().extractingPath("$.status").isEqualTo(401);
    }

    @Test
    void blankEmailIsRejected() {
        assertValidationError(login("  ", PASSWORD), "email");
    }

    @Test
    void malformedEmailIsRejected() {
        assertValidationError(login("not-an-email", PASSWORD), "email");
    }

    @Test
    void tooLongEmailIsRejected() {
        String email = "a".repeat(User.EMAIL_MAX_LENGTH - "@example.com".length() + 1) + "@example.com";

        assertValidationError(login(email, PASSWORD), "email");
    }

    @Test
    void blankPasswordIsRejected() {
        assertValidationError(login(EMAIL, " "), "password");
    }

    @Test
    void passwordOverBcryptLimitIsRejected() {
        assertValidationError(login(EMAIL, "a".repeat(73)), "password");
    }

    @Test
    void passwordLimitCountsUtf8BytesNotChars() {
        // 25 chars, but 75 bytes in UTF-8.
        assertValidationError(login(EMAIL, "€".repeat(25)), "password");
    }

    @Test
    void passwordAtBcryptLimitIsAccepted() {
        assertThat(login("auth-test-nobody@example.com", "a".repeat(72))).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void missingBodyIsRejected() {
        assertThat(mvc.post().uri("/api/auth/login").contentType(MediaType.APPLICATION_JSON))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    void malformedJsonIsRejected() {
        assertThat(mvc.post().uri("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"email\":"))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    void validationErrorDoesNotEchoPassword() throws Exception {
        String password = "secret-" + "x".repeat(80);

        MvcTestResult result = login(EMAIL, password);

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result.getResponse().getContentAsString()).doesNotContain(password);
    }

    @Test
    void passwordsAndTokensAreNotLogged(CapturedOutput output) throws Exception {
        String wrongPassword = "wrong-password-in-log-check";
        String tooLongPassword = "too-long-in-log-check-" + "x".repeat(80);

        MvcTestResult success = login(EMAIL, PASSWORD);
        login(EMAIL, wrongPassword);
        login(EMAIL, tooLongPassword);

        String token = JsonPath.read(success.getResponse().getContentAsString(), "$.accessToken");
        assertThat(output.getAll())
                .doesNotContain(PASSWORD)
                .doesNotContain(wrongPassword)
                .doesNotContain(tooLongPassword)
                .doesNotContain(token);
    }

    private MvcTestResult login(String email, String password) {
        String json = "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
        return mvc.post().uri("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json).exchange();
    }

    private void assertValidationError(MvcTestResult result, String field) {
        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        // A field can break several constraints (an over-long email is also malformed).
        assertThat(result).bodyJson().extractingPath("$.errors[*].field").asArray().containsOnly(field);
    }

    private Jwt decode(String token) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtProperties.signingKey())
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        JwtTimestampValidator timestamps = new JwtTimestampValidator();
        timestamps.setClock(Clock.fixed(NOW, ZoneOffset.UTC));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                timestamps, new JwtIssuerValidator(jwtProperties.issuer())));
        return decoder.decode(token);
    }
}
