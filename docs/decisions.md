# Architecture decisions

Short ADR-style records: context, decision, reasons, alternatives.
Status: Accepted (in the code) or Planned (agreed, not implemented yet).

## ADR-001: Monorepo
Status: Accepted

- Context: one developer; backend and frontend change together; reviewers get one link.
- Decision: one repository with `backend/`, `frontend/`, `docker-compose.yml`, `docs/` and a root `CLAUDE.md`.
- Why: one history shows a feature going through both layers; one place for AI rules and CI; path filters keep CI jobs independent.
- Alternatives: two repositories. Cleaner ownership in a larger team, but cross-cutting changes need two PRs.

## ADR-002: Spring Boot 4 on Java 21
Status: Accepted

- Decision: Spring Boot 4.1 (Spring Framework 7), Java 21 LTS.
- Why: Boot 3.x is out of open-source support; a new project starts on the current line. Java 21 is LTS with records, pattern matching and virtual threads.
- Alternatives: Boot 3.x matches many existing enterprise codebases, but would start the project on an unsupported line.

## ADR-003: Flyway owns the schema, Hibernate only validates
Status: Accepted

- Decision: every schema change is a versioned Flyway migration; `ddl-auto: validate`; applied migrations are never edited.
- Why: the schema is reviewable and reproducible; the app fails fast at startup if entities and tables drift apart.
- Alternatives: `ddl-auto: update`. Fast to start, unpredictable in production, no history.

## ADR-004: varchar + CHECK instead of Postgres enums
Status: Accepted

- Decision: enum-like columns (role, status) are `varchar` with a `CHECK (... IN (...))` constraint, mapped with `@Enumerated(EnumType.STRING)`.
- Why: adding or removing a value is an ordinary constraint change in a migration; native enums need `ALTER TYPE` and cannot drop values easily.
- Alternatives: native Postgres enum; lookup table with a foreign key.

## ADR-005: Emails stored lowercase
Status: Accepted

- Decision: a `CHECK` constraint requires lowercase emails, plus a `UNIQUE` constraint. The application lowercases with `Locale.ROOT` before saving and looking up.
- Why: case-insensitive uniqueness without `citext` or a functional index; `Locale.ROOT` avoids locale-dependent case mapping (for example the Turkish dotless i).
- Alternatives: `citext` extension; unique index on `lower(email)`.

## ADR-006: Optimistic locking on tickets
Status: Accepted

- Context: two agents may change the status of the same ticket at the same time.
- Decision: `tickets.version` column mapped with `@Version`; a conflicting update fails and the API returns 409.
- Why: no silent lost updates and no database locks held while a user is thinking.
- Alternatives: pessimistic locking (`SELECT ... FOR UPDATE`): safe but holds locks and scales worse; last write wins: simple but loses data.

## ADR-007: open-in-view disabled
Status: Accepted

- Decision: `spring.jpa.open-in-view: false`.
- Why: no lazy loading during JSON serialization and no hidden queries outside transactions; services must fetch what the response needs (fetch joins, entity graphs, projections).

## ADR-008: Backend structure
Status: Accepted (structure, DTOs, errors), Planned (API docs)

- Package by feature: `user/`, `ticket/`, `auth/`, plus `common/` for cross-cutting code (exception handling, clock). There is no `message/` package: `Message` belongs to the `Ticket` aggregate and is never changed on its own.
- `Ticket` is the aggregate root. Every message, from either role, is added through `Ticket.postMessage(...)`, which applies the status rules, auto-assignment, body validation and authorship in one place (BR-17, NFR-08). Manual status changes and assignment are also `Ticket` methods. The domain rules are plain unit tests.
- Controllers are split by role, not by entity: each role has its own controller and DTOs under its URL zone (ADR-009), so customer DTOs never contain the assignee.
- DTOs are Java records; entities never leave the service layer; mapping is written by hand (no MapStruct).
- Errors: one `@RestControllerAdvice` (`common/ApiExceptionHandler`) returns `ProblemDetail` for every error (NFR-03). Rejected values never appear in a response.
  - An API error with a fixed status (404, 409, 401 on login) extends `ErrorResponseException` and needs no new handler. Exceptions are never annotated with `@ResponseStatus`: the catch-all below runs before `ResponseStatusExceptionResolver` and would turn them into 500.
  - 400 for bean validation and for a path variable or parameter that cannot be converted (for example a non-numeric id): `errors[{field, message}]`.
  - 400 for malformed JSON and for an unknown enum value: the generic "Failed to read request" without `errors[]`, because Jackson's messages contain the rejected value. Field-level errors for these would be a change for all endpoints at once.
  - 500 for any exception without a more specific handler: a fixed detail, the exception logged with its stack trace and never returned. Without it the error would go through an ERROR dispatch to `/error`, pass the security chain again and could come back as 401, 403 or an empty body. Spring Security exceptions are rethrown, so 401 and 403 stay with Spring Security.
  - A valid token whose user is missing or has another role is an `IllegalStateException` and a 500, not a 4xx: the MVP never deletes users or changes roles, so it signals a bug, not a client error.
- API docs: springdoc-openapi (NFR-07, not yet added).
- Alternatives: package by layer (global `controllers/`, `services/`); a separate `message/` feature with its own service, which would create a second write path for messages; MapStruct. These add indirection or weaken the single write path.
- Optimistic-locking conflicts (BR-15): `ObjectOptimisticLockingFailureException` reaches the advice (from the transaction manager at commit, or from a repository call at an auto-flush) and is handled in `ApiExceptionHandler` as 409 with the fixed detail "The data was changed by another request. Reload it and try again.". The response carries no entity name, id or version. It is a framework exception, so it has its own handler; our own exceptions still extend ErrorResponseException.

## ADR-009: Authentication and authorization
Status: Accepted

- Decision: Spring Security as an OAuth2 resource server with JWTs signed by the application's own key. No custom security filters.
- Tokens: issued at `POST /api/auth/login` (FR-01) by `NimbusJwtEncoder`, HS256 with a symmetric key from `app.jwt.secret` (Base64, at least 256 bits, no default; `APP_JWT_SECRET` outside demo and tests). Claims: `iss`, `sub` (user id), `role`, `iat`, `exp`; no email or other personal data. Lifetime 60 minutes, no refresh token (NFR-01).
- Verification: `NimbusJwtDecoder` accepts HS256 only and validates the issuer, `exp` (required) and `nbf` against the application clock with the default 60 s skew.
- URL zones: `/api/customer/**` requires CUSTOMER, `/api/agent/**` requires AGENT, `POST /api/auth/login`, `GET /actuator/health` and `/error` are public, everything else is denied. Each role has its own endpoints and DTOs, so fields such as the assignee cannot leak to customers, and BR-02 is enforced in one place.
- Authorities: the `role` claim maps to `ROLE_<role>`. A valid token with a missing or unknown role gets no role authority and receives 403, not 401 (the caller is authenticated but not permitted, RFC 6750).
- Errors: 401 and 403 are Problem Details built by the common exception handler (NFR-03). Every rejected token gets the same body and `WWW-Authenticate: Bearer error="invalid_token"` without a description; the reason (expired, forged, malformed) is never disclosed.
- Stateless sessions; CSRF, form login, HTTP basic and logout are disabled because the token travels in the Authorization header, not in a cookie. No CORS: the frontend reaches the API same-origin through a dev proxy, and through a reverse proxy in production.
- Authorization rule: a customer sees only their own tickets, enforced in the query (`findByIdAndCustomerId`), not by filtering after loading. Another customer's ticket returns 404, the same as a missing one (BR-01). This prevents IDOR.
- Production note: tokens would come from a corporate identity provider (Keycloak, Azure AD) signed with RS256 and published via JWKS; the backend is already a resource server, so only the decoder configuration changes.
- Alternatives: server sessions with cookies; a hand-written JWT filter; shared API paths with method security (`@PreAuthorize`) and per-role serialization.

## ADR-010: Chat delivery
Status: Planned

- Decision: start with REST plus polling every 3-5 seconds, only while a ticket chat is open. Later: Server-Sent Events.
- Why not WebSocket: messages are sent through REST anyway; push is needed in one direction only. SSE is simpler to run and secure.
- Known issue: the browser `EventSource` cannot send an `Authorization` header, so SSE will need a fetch-based client or another token transport.

## ADR-011: One Angular app with two zones
Status: Accepted

- Context: an earlier implementation of the same domain had separate client and staff frontends.
- Decision: one Angular app with lazy-loaded `/client` and `/staff` route trees, each protected by a functional `canMatch` guard. No role goes to `/login`; a wrong role goes to the user's own zone.
- Why `canMatch`: an unauthorized user does not even download the other zone's code chunk.
- Roles are exactly `CUSTOMER` and `AGENT`, as in the database `CHECK` constraint; the role-to-zone mapping lives in one file (`core/auth/user-role.ts`).
- Alternatives: two separate apps or an Nx workspace. Duplicated shell, build and dependencies for no real gain at this size.

## ADR-012: Frontend stack and defaults
Status: Accepted

- Angular 22.2, created with `npx @angular/cli@22.2.2` (no global CLI, the version is pinned explicitly). Node 24.21 (Angular 22 requires 24.15+).
- Current Angular defaults kept: standalone components, signals, OnPush, zoneless change detection, Vitest (no browser needed in CI).
- Angular Material (M3 theme, azure-blue) over PrimeNG: official, released in lockstep with Angular, stable API.
- Known trade-off: fonts (Roboto, Material Symbols) load from the Google Fonts CDN. On a closed corporate network they would be self-hosted.

## ADR-013: Demo account seeding
Status: Accepted

- Decision: demo accounts (2 agents, 2 customers on example.com) are created by `DemoAccountSeeder`, 
an `ApplicationRunner` under `@Profile("demo")`. It inserts an account only if its email is missing, so restarts are idempotent. 
All accounts share one password from `app.seed.demo-password` (`application-demo.yaml`, overridable via `APP_SEED_DEMOPASSWORD`); 
there is no default in code and startup fails if it is missing or blank. Passwords are hashed with the shared BCrypt `PasswordEncoder` bean, the same one login uses.