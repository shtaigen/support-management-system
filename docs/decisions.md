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
Status: Accepted (column), Planned (entity mapping)

- Context: two agents may change the status of the same ticket at the same time.
- Decision: `tickets.version` column mapped with `@Version`; a conflicting update fails and the API returns 409.
- Why: no silent lost updates and no database locks held while a user is thinking.
- Alternatives: pessimistic locking (`SELECT ... FOR UPDATE`): safe but holds locks and scales worse; last write wins: simple but loses data.

## ADR-007: open-in-view disabled
Status: Accepted

- Decision: `spring.jpa.open-in-view: false`.
- Why: no lazy loading during JSON serialization and no hidden queries outside transactions; services must fetch what the response needs (fetch joins, entity graphs, projections).

## ADR-008: Backend structure
Status: Planned

- Package by feature: `ticket/`, `message/`, `user/`, `auth/`, each with its own controller, service and repository.
- DTOs are Java records; entities never leave the service layer; mapping is written by hand (no MapStruct).
- Status transitions live in the `Ticket` entity (`onCustomerMessage()`, `changeStatus(...)`), so the domain rules are plain unit tests.
- Errors: `@RestControllerAdvice` returning `ProblemDetail`. API docs: springdoc-openapi.
- Alternatives: package by layer (global `controllers/`, `services/`); MapStruct. Both work, but add indirection that is harder to explain and navigate.

## ADR-009: Authentication and authorization
Status: Planned

- Decision: Spring Security as an OAuth2 resource server with JWTs signed by the application's own key (`NimbusJwtEncoder`). No custom security filters.
- Production note: tokens would come from a corporate identity provider (Keycloak, Azure AD); the backend is already a resource server.
- Authorization rule: a customer sees only their own tickets, enforced in the query (`findByIdAndCustomerId`), not by filtering after loading. This prevents IDOR.
- Alternatives: server sessions with cookies; a hand-written JWT filter.

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