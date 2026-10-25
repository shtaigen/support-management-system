package io.github.shtaigen.support;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

// NFR-07. The expected operations are written out here, not read back from the spec: each error
// code follows the FR Errors row, plus 401 when authenticated, plus 400 and 403 wherever the
// operation can return them. The success code follows the controller (ResponseEntity.created is
// 201, a plain return value is 200).
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiIntegrationTest {

    private static final String PROBLEM_REF = "#/components/schemas/ProblemDetail";
    private static final String CUSTOMER = "Customer tickets";
    private static final String AGENT = "Agent tickets";

    private record Op(String fr, String method, String path, String tag, String success, Set<String> errors,
                      boolean authenticated, Set<String> parameters) {

        String key() {
            return method + " " + path;
        }
    }

    private static final List<Op> OPERATIONS = List.of(
            new Op("FR-01", "post", "/api/auth/login", "Auth", "200", Set.of("400", "401"), false, Set.of()),
            new Op("FR-03", "post", "/api/customer/tickets", CUSTOMER, "201",
                    Set.of("400", "401", "403"), true, Set.of()),
            new Op("FR-04", "get", "/api/customer/tickets", CUSTOMER, "200",
                    Set.of("400", "401", "403"), true, Set.of("status")),
            new Op("FR-05", "get", "/api/customer/tickets/{id}", CUSTOMER, "200",
                    Set.of("400", "401", "403", "404"), true, Set.of("id")),
            new Op("FR-06", "post", "/api/customer/tickets/{id}/messages", CUSTOMER, "200",
                    Set.of("400", "401", "403", "404", "409"), true, Set.of("id")),
            new Op("FR-07", "get", "/api/agent/tickets", AGENT, "200",
                    Set.of("400", "401", "403"), true, Set.of("status", "assigneeId", "unassigned", "customerId")),
            new Op("FR-08", "get", "/api/agent/customers", "Agent customers", "200",
                    Set.of("401", "403"), true, Set.of()),
            new Op("FR-09", "get", "/api/agent/tickets/{id}", AGENT, "200",
                    Set.of("400", "401", "403", "404"), true, Set.of("id")),
            new Op("FR-10", "post", "/api/agent/tickets/{id}/messages", AGENT, "200",
                    Set.of("400", "401", "403", "404", "409"), true, Set.of("id")),
            new Op("FR-11", "put", "/api/agent/tickets/{id}/status", AGENT, "200",
                    Set.of("400", "401", "403", "404", "409"), true, Set.of("id")),
            new Op("FR-12", "put", "/api/agent/tickets/{id}/assignee", AGENT, "200",
                    Set.of("400", "401", "403", "404", "409"), true, Set.of("id")));

    private static final Set<String> HTTP_METHODS = Set.of("get", "put", "post", "delete", "options", "head",
            "patch", "trace");

    @Autowired
    private MockMvcTester mvc;

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void specIsPublicJson() {
        MvcTestResult result = mvc.get().uri("/v3/api-docs").exchange();

        assertThat(result).hasStatusOk().hasContentTypeCompatibleWith(MediaType.APPLICATION_JSON);
    }

    @Test
    void specListsExactlyTheApiOperations() {
        Set<String> actual = new TreeSet<>();
        for (Map.Entry<String, JsonNode> path : spec().get("paths").properties()) {
            for (Map.Entry<String, JsonNode> method : path.getValue().properties()) {
                if (HTTP_METHODS.contains(method.getKey())) {
                    actual.add(method.getKey() + " " + path.getKey());
                }
            }
        }
        Set<String> expected = new TreeSet<>();
        OPERATIONS.forEach(op -> expected.add(op.key()));

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void eachOperationDeclaresExactlyItsResponseCodes() {
        JsonNode spec = spec();
        SoftAssertions softly = new SoftAssertions();
        for (Op op : OPERATIONS) {
            JsonNode responses = operation(spec, op).get("responses");
            Set<String> expected = new TreeSet<>(op.errors());
            expected.add(op.success());
            softly.assertThat(new TreeSet<>(responses.propertyNames())).as(op.key()).isEqualTo(expected);

            for (String code : op.errors()) {
                JsonNode content = responses.path(code).path("content");
                softly.assertThat(new TreeSet<>(content.propertyNames())).as(op.key() + " " + code)
                        .containsExactly(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
                softly.assertThat(content.path(MediaType.APPLICATION_PROBLEM_JSON_VALUE).path("schema").path("$ref")
                        .asString()).as(op.key() + " " + code).isEqualTo(PROBLEM_REF);
            }

            // The success response keeps the schema generated from the return type.
            JsonNode success = responses.path(op.success()).path("content");
            softly.assertThat(new TreeSet<>(success.propertyNames())).as(op.key() + " success content")
                    .containsExactly(MediaType.APPLICATION_JSON_VALUE);
            for (Map.Entry<String, JsonNode> media : success.properties()) {
                JsonNode schema = media.getValue().path("schema");
                softly.assertThat(schema.isMissingNode()).as(op.key() + " success schema").isFalse();
                softly.assertThat(schema.path("$ref").asString()).as(op.key() + " success schema")
                        .isNotEqualTo(PROBLEM_REF);
            }
        }
        softly.assertAll();
    }

    @Test
    void eachOperationHasItsFrSummaryAndTag() {
        JsonNode spec = spec();
        SoftAssertions softly = new SoftAssertions();
        for (Op op : OPERATIONS) {
            JsonNode operation = operation(spec, op);
            softly.assertThat(operation.path("summary").asString()).as(op.key()).startsWith(op.fr() + ":");
            softly.assertThat(strings(operation.path("tags"))).as(op.key()).containsExactly(op.tag());
        }
        softly.assertAll();
    }

    @Test
    void bearerSchemeAppliesToAuthenticatedOperationsOnly() {
        JsonNode spec = spec();
        JsonNode scheme = spec.path("components").path("securitySchemes").path("bearer");
        assertThat(scheme.path("type").asString()).isEqualTo("http");
        assertThat(scheme.path("scheme").asString()).isEqualTo("bearer");
        assertThat(scheme.path("bearerFormat").asString()).isEqualTo("JWT");

        SoftAssertions softly = new SoftAssertions();
        for (Op op : OPERATIONS) {
            JsonNode operation = operation(spec, op);
            if (op.authenticated()) {
                // An operation without its own requirement inherits the global one.
                JsonNode security = operation.has("security") ? operation.get("security") : spec.path("security");
                softly.assertThat(security.toString()).as(op.key()).isEqualTo("[{\"bearer\":[]}]");
            } else {
                softly.assertThat(operation.has("security")).as(op.key() + " overrides the global requirement")
                        .isTrue();
                softly.assertThat(operation.path("security").toString()).as(op.key()).isEqualTo("[]");
            }
        }
        softly.assertAll();
    }

    @Test
    void operationsHaveOnlyTheirDocumentedParameters() {
        JsonNode spec = spec();
        SoftAssertions softly = new SoftAssertions();
        for (Op op : OPERATIONS) {
            Set<String> names = new TreeSet<>();
            operation(spec, op).path("parameters").forEach(parameter -> names.add(parameter.path("name").asString()));
            softly.assertThat(names).as(op.key()).isEqualTo(new TreeSet<>(op.parameters()));
        }
        softly.assertAll();
    }

    @Test
    void specKeysAreSorted() {
        JsonNode spec = spec();
        List<String> paths = new ArrayList<>(spec.get("paths").propertyNames());
        List<String> schemas = new ArrayList<>(spec.path("components").path("schemas").propertyNames());

        assertThat(paths).hasSizeGreaterThan(1).isSorted();
        assertThat(schemas).hasSizeGreaterThan(1).isSorted();
    }

    @Test
    void swaggerUiIsPublic() {
        MvcTestResult redirect = mvc.get().uri("/swagger-ui.html").exchange();
        assertThat(redirect).hasStatus3xxRedirection();
        String location = redirect.getResponse().getHeader(HttpHeaders.LOCATION);
        assertThat(location).isEqualTo("/swagger-ui/index.html");

        assertThat(mvc.get().uri(location).exchange()).hasStatusOk();
        assertThat(mvc.get().uri("/v3/api-docs/swagger-config").exchange()).hasStatusOk();
        assertThat(mvc.get().uri("/v3/api-docs.yaml").exchange()).hasStatusOk();
    }

    @Test
    void apiStillNeedsTokenAndUnknownPathsStayDenied() {
        assertThat(mvc.get().uri("/api/customer/tickets").exchange()).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(mvc.get().uri("/api/agent/tickets").exchange()).hasStatus(HttpStatus.UNAUTHORIZED);

        SimpleGrantedAuthority agent = new SimpleGrantedAuthority("ROLE_AGENT");
        for (String path : new String[] {"/v3/other", "/swagger-uiX"}) {
            assertThat(mvc.get().uri(path).exchange()).as(path).hasStatus(HttpStatus.UNAUTHORIZED);
            assertThat(mvc.get().uri(path).with(jwt().authorities(agent)).exchange()).as(path)
                    .hasStatus(HttpStatus.FORBIDDEN);
        }
        // The docs rule is GET only.
        assertThat(mvc.post().uri("/v3/api-docs").with(jwt().authorities(agent)).exchange())
                .hasStatus(HttpStatus.FORBIDDEN);
    }

    // --- helpers ---

    private JsonNode spec() {
        MvcTestResult result = mvc.get().uri("/v3/api-docs").exchange();
        assertThat(result).hasStatusOk();
        // JsonMapper keeps the key order of the raw response, which specKeysAreSorted relies on.
        return json.readTree(result.getResponse().getContentAsByteArray());
    }

    private static JsonNode operation(JsonNode spec, Op op) {
        JsonNode operation = spec.path("paths").path(op.path()).path(op.method());
        assertThat(operation.isMissingNode()).as(op.key() + " present").isFalse();
        return operation;
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.asString()));
        return values;
    }
}
