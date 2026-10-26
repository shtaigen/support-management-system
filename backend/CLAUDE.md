# Backend CLAUDE.md

## Commands (run from `backend/`, PowerShell)
- Build + tests: `.\mvnw.cmd verify`
- Tests only: `.\mvnw.cmd test`
- Run app: `.\mvnw.cmd spring-boot:run`
- Run app with demo accounts: `.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=demo"`

Tests and the app need the Postgres container running (`docker compose up -d` from the repo
root). There is no Testcontainers or in-memory DB: `@SpringBootTest` connects to the real DB.

## Flyway
- `V1__initial_schema.sql` is frozen. Never edit, rename or delete an applied migration.
- Every schema change goes into a new `V2__...`, `V3__...` file in `src/main/resources/db/migration`.
- `spring.jpa.hibernate.ddl-auto` is `validate`: entities must match the migrations exactly
  (names, types, nullability). Never switch it to `update`/`create`.

## Schema conventions (follow V1)
- Enum-like columns are `varchar` + `CHECK (... IN (...))`, never native Postgres enums.
  Map them in JPA with `@Enumerated(EnumType.STRING)`.
- Emails are stored lowercase (`users_email_lowercase_ck`). Lowercase before every save and
  lookup with `toLowerCase(Locale.ROOT)`, never the default locale.
- Every foreign key has an index (a composite index with the FK as leading column counts).
- `tickets.version` is for optimistic locking: map it with `@Version` on the ticket entity.
- Identity PKs (`GENERATED ALWAYS AS IDENTITY`), `timestamptz` for timestamps.
- Constraint names follow `<table>_<column>_{fk,ck,uk}`, indexes `<table>_<columns>_idx`.

## JPA
`spring.jpa.open-in-view` is `false`: lazy associations must be loaded inside the
service/transaction (fetch joins, entity graphs or DTO projections), not in controllers.
In `AgentTicketService` the flush lives in the private `respond` method, which `postMessage`, `changeStatus` and `assign` all use. Build every write response through it.

When a response shows `version` or `updatedAt` of an entity changed in the same request,
call `flush()` on the repository before building the response. `@Version` and `@PreUpdate`
take effect at flush, and a later flush runs `@PreUpdate` again with a new time, so a
response built before the flush differs from the database. An optimistic-locking conflict
then surfaces at that flush and still becomes the 409 (ADR-008). Example:
`AgentTicketService.postMessage`.

## Deliberately absent
Lombok and Testcontainers are not in the project on purpose.
Do not add them (or alternatives) without asking first.

## Spring Security
`spring-boot-starter-security-oauth2-resource-server` (ADR-009) brings in Spring Security web,
the resource server, jose (`NimbusJwtEncoder`/`NimbusJwtDecoder`) and crypto (the shared BCrypt
`PasswordEncoder`). Tests get `spring-security-test` through the matching `-test` starter. Do not
add other security dependencies without asking first.

- `auth/JwtConfig` signs tokens and `auth/SecurityConfig` verifies them, both with
  `JwtProperties.signingKey()`. The decoder accepts HS256 only and checks issuer, `exp` (required)
  and `nbf` against the `Clock` bean, with the default 60 s skew.
- The `role` claim maps to `ROLE_CUSTOMER` or `ROLE_AGENT` (`RoleClaimAuthoritiesConverter`). A
  missing or unknown role gives no role authority, so the request gets 403. The principal name is
  `sub` (the user id). Spring Security 7 also adds a `FACTOR_BEARER` authority; ignore it.
- URL rules in `SecurityConfig`, with deny by default:

  | Path | Access |
  |---|---|
  | `POST /api/auth/login`, `GET /actuator/health`, `/error` | public |
  | `GET /v3/api-docs`, `/v3/api-docs/**`, `/v3/api-docs.yaml`, `/swagger-ui.html`, `/swagger-ui/**` | public (GET only) |
  | `/api/customer/**` | CUSTOMER |
  | `/api/agent/**` | AGENT |
  | anything else | denied (401 without a token, 403 with one) |

  A new endpoint must live under its role's prefix, or it is unreachable. Each role gets its own
  endpoints and DTOs, so fields such as the assignee cannot leak to customers. There is no method
  security (`@PreAuthorize`) yet.
- 401 and 403 are Problem Details built by `ApiExceptionHandler`: `SecurityProblemHandler` passes an
  `ErrorResponseException` to the MVC exception resolver. Every rejected token gets the same body
  and `WWW-Authenticate: Bearer error="invalid_token"` (plain `Bearer` when no token was sent). Never
  add the rejection reason to the body or the header.
- Sessions are stateless. CSRF, form login, HTTP basic and logout are disabled (bearer header, no
  cookies). There is no CORS on purpose: the Angular dev server proxies `/api` to the backend
  (`frontend/proxy.conf.json`), so calls stay same-origin.

JWT signing key `app.jwt.secret`: Base64, at least 32 random bytes after decoding. There is no
default in `application.yaml`, and startup fails if it is missing or too short.
- Prod and other runs: set `APP_JWT_SECRET`.
- Demo profile: `application-demo.yaml` has a dev-only key.
- Tests and CI: `src/test/resources/config/application.yaml` has a test-only key. It sits under
  `config/` on purpose: a test-root `application.yaml` would replace the main one.
- Generate a key: `openssl rand -base64 32`.

Never log passwords, hashes or tokens (NFR-02). Request/response records that hold them mask
`toString()`.

## OpenAPI (NFR-07, ADR-008)
springdoc 3.2.0, configured in `common/OpenApiConfig` (API info, bearer scheme, global security
requirement) and in `application.yaml` (`springdoc.*`). A new endpoint needs:
- `@Tag` on its controller and `@Operation(summary = "FR-xx: ...")` on the method.
- A bare success `@ApiResponse` (200, or 201 for a create): once any `@ApiResponse` is declared,
  springdoc no longer generates the success response. Its schema still comes from the return type.
- An `@ApiResponse` with the `ProblemDetail` schema (`application/problem+json`) for every 4xx it
  can return: its FR Errors row, 401 when authenticated, 403 for the wrong role, 400 when a path
  or query value can fail conversion. Never declare 500.
- An entry in the tables of `OpenApiIntegrationTest` (operations, response codes, parameters).
Do not add `produces` (the default is `application/json`). A public endpoint opts out of the
global security requirement with an empty `@SecurityRequirements`, as login does. Never use
`@ResponseStatus` to describe a response, although the springdoc documentation suggests it.

## Exceptions and tests
- Never annotate exceptions with @ResponseStatus: the catch-all @ExceptionHandler(Exception.class) in ApiExceptionHandler runs first and would turn them into 500. 
An API error with a fixed status extends ErrorResponseException (see auth/InvalidCredentialsException, ticket/TicketNotFoundException).

- Tie-break tests (an order such as `lastMessageAt desc, id desc`) use at least six rows with the same leading value. With fewer rows, Postgres can return tied rows in the expected order by accident, and the tie-break mutation survives (step 5e).