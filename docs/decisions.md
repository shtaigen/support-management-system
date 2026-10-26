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

- Context: two agents may change the status or the assignee of the same ticket at the same time, or an agent may act on a ticket view that is already stale.
- Decision: `tickets.version` column mapped with `@Version`; a conflicting update fails and the API returns 409.
- Client version (FR-11, FR-12): the request carries the version from the agent's FR-09 view. The service compares it with the loaded ticket's version before calling the domain method and, when they differ, throws `ObjectOptimisticLockingFailureException(Ticket.class, id)`, so the client gets the same 409 with the same detail (ADR-008). `@Version` alone compares with the version read in the same transaction, so it cannot detect a stale view.
- Two layers: the explicit comparison catches a stale view; `@Version` at flush catches a change committed between the comparison and the flush.
- Messages (FR-06, FR-10) carry no version: a reply is not a decision based on the ticket's state, so only the second layer applies.
- Why: no silent lost updates and no database locks held while a user is thinking.
- Alternatives: pessimistic locking (`SELECT ... FOR UPDATE`): safe but holds locks and scales worse; last write wins: simple but loses data; the version in an `If-Match` header with an `ETag` response header: standard HTTP conditional requests, but more work in the client and in the error mapping; merging a detached entity that carries the client version: copies every field from the request and hides what changed.

## ADR-007: open-in-view disabled
Status: Accepted

- Decision: `spring.jpa.open-in-view: false`.
- Why: no lazy loading during JSON serialization and no hidden queries outside transactions; services must fetch what the response needs (fetch joins, entity graphs, projections).

## ADR-008: Backend structure
Status: Accepted

- Package by feature: `user/`, `ticket/`, `auth/`, plus `common/` for cross-cutting code (exception handling, clock). There is no `message/` package: `Message` belongs to the `Ticket` aggregate and is never changed on its own.
- `Ticket` is the aggregate root. Every message, from either role, is added through `Ticket.postMessage(...)`, which applies the status rules, auto-assignment, body validation and authorship in one place (BR-17, NFR-08). Manual status changes and assignment are also `Ticket` methods. The domain rules are plain unit tests.
- Controllers are split by role, not by entity: each role has its own controller and DTOs under its URL zone (ADR-009), so customer DTOs never contain the assignee.
- DTOs are Java records; entities never leave the service layer; mapping is written by hand (no MapStruct).
- Errors: one `@RestControllerAdvice` (`common/ApiExceptionHandler`) returns `ProblemDetail` for every error (NFR-03). Rejected values never appear in a response.
  - An API error with a fixed status (404, 409, 401 on login) extends `ErrorResponseException` and needs no new handler. Exceptions are never annotated with `@ResponseStatus`: the catch-all below runs before `ResponseStatusExceptionResolver` and would turn them into 500.
  - 400 for bean validation and for a path variable or parameter that cannot be converted (for example a non-numeric id): `errors[{field, message}]`.
  - 400 for malformed JSON and for an unknown enum value: the generic "Failed to read request" without `errors[]`, because Jackson's messages contain the rejected value. Field-level errors for these would be a change for all endpoints at once.
  - 400 for a field that fails a service-side check (FR-12 assignee): `common/InvalidFieldException` extends `ErrorResponseException` and builds its body with `ApiExceptionHandler.validationProblem`, so the body is the same `errors[]` shape as bean validation. It never carries the rejected value. No separate handler.
  - 409 for an optimistic-locking conflict (BR-15): `ObjectOptimisticLockingFailureException` reaches the advice and is handled in `ApiExceptionHandler` with the fixed detail "The data was changed by another request. Reload it and try again.". It comes from the transaction manager at commit, from a repository call at an auto-flush, or from an explicit repository `flush()`; the agent reply (FR-10) flushes before building its response, so its conflict surfaces there. It is a framework exception, so it has its own handler.
    Sources: `@Version` at the explicit `tickets.flush()` before building the response (FR-10, FR-11, FR-12), `@Version` at commit, and the explicit comparison of the client's version with the loaded entity (FR-11, FR-12; ADR-006). All three give the same 409 body.
  - 409 for an invalid status transition (section 2 of the requirements, BR-06, BR-07): `InvalidTicketTransitionException` is thrown by the `Ticket` aggregate and handled in `ApiExceptionHandler` with the fixed detail "The ticket's current status does not allow this action.". It is a domain exception, and the domain does not depend on Spring Web, so it has its own handler instead of extending `ErrorResponseException`.
  - The two 409 causes have different fixed details. Neither response carries an entity name, id, version or status value, and the exception message is never used.
  - 500 for any exception without a more specific handler: a fixed detail, the exception logged with its stack trace and never returned. Without it the error would go through an ERROR dispatch to `/error`, pass the security chain again and could come back as 401, 403 or an empty body. Spring Security exceptions are rethrown, so 401 and 403 stay with Spring Security.
  - A valid token whose user is missing or has another role is an `IllegalStateException` and a 500, not a 4xx: the MVP never deletes users or changes roles, so it signals a bug, not a client error.
- Integer fields in JSON bodies do not accept floating-point values (spring.jackson.deserialization.accept-float-as-int: false). A value like 0.9 is an unreadable body and gets the generic 400, the same as a non-numeric value. Reason: truncation would turn an invalid version into a valid one and pass the optimistic locking check.
- OpenAPI (NFR-07, step 6a):
  - `springdoc-openapi-starter-webmvc-ui` 3.2.0 (the 3.x line targets Spring Boot 4; 3.2.0 is built against Spring Boot 4.1). Through swagger-core it brings Jackson 2 (`com.fasterxml.jackson`), used only to write the spec; the API itself still serializes with Jackson 3.
  - Public in every profile, GET only: `/v3/api-docs`, `/v3/api-docs/**`, `/v3/api-docs.yaml`, `/swagger-ui.html`, `/swagger-ui/**`. Other methods on these paths and all other paths keep the existing rules, including denyAll for unknown paths. springdoc logs a startup warning that the docs are enabled in production; this is expected.
  - `common/OpenApiConfig` is the one place for the API info, the security scheme (HTTP bearer, JWT) and the global security requirement. Login opts out with an empty `@SecurityRequirements`, so its operation has `security: []`.
  - Each operation declares, with `@ApiResponse` and the `ProblemDetail` schema (`application/problem+json`), every 4xx it can actually return: the codes in its FR Errors row, 401 on every authenticated operation, 403 for the wrong role, and 400 wherever a path or query value can fail conversion. 500 is never declared: the catch-all is not part of the API contract.
  - Once an operation declares any `@ApiResponse`, springdoc stops generating its success response, so each operation also declares a bare success `@ApiResponse` (200, or 201 for FR-03). Its schema is still generated from the return type.
  - `springdoc.default-produces-media-type: application/json`: success responses are `application/json` without `produces` on the controllers.
  - `springdoc.override-with-generic-response: false`. With today's handlers (no `@ResponseStatus`) it has no visible effect (in step 6a the mutation produced an identical spec); it guards against advice responses being copied into every operation later.
  - `springdoc.writer-with-order-by-keys: true`, so the spec is deterministic.
  - The authenticated user id (`@CurrentUserId`) does not appear as a parameter: springdoc ignores `@AuthenticationPrincipal` parameters, also through the meta-annotation (checked in step 6a, covered by a test).
  - Each controller has `@Tag`; each operation has `@Operation(summary = "FR-xx: ...")`. Operation ids are generated (for example `list_2`); set them explicitly only if a generated client needs them.
  - No `@ResponseStatus` on exceptions, although the springdoc documentation suggests it for describing responses.
  - `OpenApiIntegrationTest` checks the generated spec against hardcoded tables of operations, response codes and parameters.
- Alternatives: package by layer (global `controllers/`, `services/`); a separate `message/` feature with its own service, which would create a second write path for messages; MapStruct. These add indirection or weaken the single write path.

## ADR-009: Authentication and authorization
Status: Accepted

- Decision: Spring Security as an OAuth2 resource server with JWTs signed by the application's own key. No custom security filters.
- Tokens: issued at `POST /api/auth/login` (FR-01) by `NimbusJwtEncoder`, HS256 with a symmetric key from `app.jwt.secret` (Base64, at least 256 bits, no default; `APP_JWT_SECRET` outside demo and tests). Claims: `iss`, `sub` (user id), `role`, `iat`, `exp`; no email or other personal data. Lifetime 60 minutes, no refresh token (NFR-01).
- Verification: `NimbusJwtDecoder` accepts HS256 only and validates the issuer, `exp` (required) and `nbf` against the application clock with the default 60 s skew.
- URL zones: `/api/customer/**` requires CUSTOMER, `/api/agent/**` requires AGENT, `POST /api/auth/login`, `GET /actuator/health`, `/error` and the GET-only API docs paths (ADR-008) are public, everything else is denied. Each role has its own endpoints and DTOs, so fields such as the assignee cannot leak to customers, and BR-02 is enforced in one place.
- Authorities: the `role` claim maps to `ROLE_<role>`. A valid token with a missing or unknown role gets no role authority and receives 403, not 401 (the caller is authenticated but not permitted, RFC 6750).
- Errors: 401 and 403 are Problem Details built by the common exception handler (NFR-03). Every rejected token gets the same body and `WWW-Authenticate: Bearer error="invalid_token"` without a description; the reason (expired, forged, malformed) is never disclosed.
- Stateless sessions; CSRF, form login, HTTP basic and logout are disabled because the token travels in the Authorization header, not in a cookie. No CORS: the frontend reaches the API same-origin through a dev proxy, and through a reverse proxy in production.
- Authorization rule: a customer sees only their own tickets, enforced in the query (`findByIdAndCustomerId`), not by filtering after loading. Another customer's ticket returns 404, the same as a missing one (BR-01). This prevents IDOR.
- Production note: tokens would come from a corporate identity provider (Keycloak, Azure AD) signed with RS256 and published via JWKS; the backend is already a resource server, so only the decoder configuration changes.
- Alternatives: server sessions with cookies; a hand-written JWT filter; shared API paths with method security (`@PreAuthorize`) and per-role serialization.

## ADR-010: Chat delivery
Status: Accepted for polling (implemented in the customer zone, details in ADR-015); SSE is still Planned.

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

## ADR-014: Frontend authentication

**Status:** Accepted
**Date:** 2026-10-25

### Context
The backend issues a stateless JWT on `POST /api/auth/login` (FR-01) and has no refresh token (NFR-01). The response shape, verified against a live response through the dev proxy, is:

    {
      "accessToken": "<JWT>",
      "expiresAt": "2026-10-25T14:52:29Z",
      "user": { "id": 1, "name": "Alice Agent", "role": "AGENT" }
    }

The token lives one hour. Logout (FR-02) is client-only. The zones `/client` and `/staff` are lazy routes guarded by `canMatch` (ADR-011). The frontend reaches the API only through the same origin (ADR-009). Errors follow RFC 9457 (ADR-008). A failed login returns 401 with `detail` "Invalid email or password." for both unknown email and wrong password.

### Decision

#### 1. Storage
- The whole login response is stored as JSON in `sessionStorage` under one key, `sms.session`.
- Not memory only: a reload would log the user out, which is poor UX with polling (NFR-06).
- Not `localStorage`: it is shared across tabs and survives a browser restart. With `sessionStorage` each tab is an independent session, and the session ends when the tab closes.
- No browser storage protects the token from XSS (a script running in the page can also send requests). XSS is prevented instead: Angular template escaping, no `innerHTML` with user data.

#### 2. Expiry
- The client never decodes the JWT; the token is an opaque string. Expiry comes from `expiresAt`.
- A session is valid when it exists and `Date.now() < Date.parse(expiresAt)`. This is the method `hasValidSession()`, not a `computed`: signals do not track time, so a `computed` would keep an expired session valid.
- An expired session is removed on startup, and guards reject it.
- No logout timer and no clock-skew margin. The server is the source of truth: any 401 ends the session.

#### 3. 401 and 403
- 401 from `POST /api/auth/login`: shown in the login form (`detail`); no logout.
- Any other 401: `logout({ expired: true })`, which navigates to `/login?expired=1`; the login page shows "Your session has expired. Please sign in again."
- `logout()` is idempotent: without a session it does nothing and does not navigate, so parallel 401s (polling plus a user action) cause one navigation.
- 403 does not log out: the session is valid, the screen handles it.
- No return URL: after login the user always lands on the home page of their zone. A return URL would need open-redirect and cross-zone checks.

#### 4. Interceptors
- Functional interceptors: `provideHttpClient(withInterceptors([authInterceptor, unauthorizedInterceptor]))`.
- `authInterceptor` adds `Authorization: Bearer <accessToken>` only when `req.url` starts with `/api/` and is not exactly `/api/auth/login`. Absolute URLs never receive the token. The token is read at request time, not captured. Without a session the request is sent without the header. No client-side expiry check before sending.
- `unauthorizedInterceptor`: on a 401 from any URL other than `/api/auth/login` it calls `logout({ expired: true })` and rethrows the error. Screens do not show their own message for 401.
- Dependencies are taken with `inject()` inside the interceptor function; no manual `Injector` workaround for the `AuthService` → `HttpClient` dependency.

#### 5. Guards and routes
- Zone guard (`canMatch` on `/client` and `/staff`): no valid session → `/login`; role does not match the zone → the user's own zone. Guards return a `UrlTree`, never call `router.navigate()`.
- Guest guard on `/login`: a valid session → the user's own zone.
- `''` and `**`: functional `redirectTo` → own zone with a valid session, otherwise `/login`.
- Guards are UX and bundle economy (`canMatch` does not download a foreign zone's code), not security. The server enforces access (ADR-009).

#### 6. Server errors
- `core/http/` holds a `ProblemDetail` type (`status`, `title`, `detail?`, `errors?: { field, message }[]`) and `toProblem(error: HttpErrorResponse)`.
- A body is accepted as ProblemDetail only if it is an object with a numeric `status`. Otherwise: status 0 → "Cannot reach the server. Please try again."; anything else → "Something went wrong. Please try again."
- The login form shows only `detail`. Mapping `errors[]` to form controls is deferred to the customer zone (FR-03).

#### 7. Login screen
- Typed Reactive Forms (`nonNullable`). Signal Forms are stable since Angular 22, but Reactive Forms remain the standard in existing codebases; revisit later.
- Validators: both fields required, email format. Any other rule (length limits) is taken from `docs/requirements.md` and the OpenAPI spec, never invented.
- A `submitting` signal disables the button and shows a spinner; the submit handler also ignores submits while a request is pending (Enter submits even with a disabled button).
- On success: navigate to the user's zone with `replaceUrl: true`, so Back does not return to the form.
- The `?expired=1` message is shown until the first submit; the error from `toProblem()` is shown until the next submit. The error block has `role="alert"`.
- `type="email"` with `autocomplete="username"`, `type="password"` with `autocomplete="current-password"`.
- Material: `mat-card`, `mat-form-field` with `matInput`, `mat-flat-button`, `mat-progress-spinner`.

#### 8. AuthService
- `providedIn: 'root'`. A private `signal<Session | null>` exposed via `asReadonly()`; `computed` for `user`.
- The session is restored synchronously in the constructor; no app initializer.
- Defensive parsing: corrupt JSON, missing fields, unparsable `expiresAt` or an unknown role → remove the key, session `null`.
- Storage is injected through a `SESSION_STORAGE` token (factory `() => sessionStorage`); the key `sms.session` is a single constant.
- `login(email, password): Observable<Session>` stores the session on success; the component navigates.
- `logout(options?: { expired?: boolean })` is the single path for FR-02 and for 401.
- No cross-tab synchronization (no `storage` event): sessions are per tab by design.

### Tests
Vitest with `provideHttpClientTesting` and `HttpTestingController`; guards, redirects and interceptors through `TestBed.runInInjectionContext`; time through `vi.setSystemTime`. Required cases:
- AuthService: restore a valid session; restore an expired one (key removed); corrupt JSON; unknown role; login stores the session; logout clears and navigates; a second logout does not navigate; `logout({ expired: true })` → `/login?expired=1`; a session that expires after login is no longer valid (fails if implemented with `computed`).
- authInterceptor: header on `/api/...`; no header on the login URL; no header on an absolute URL; no header without a session; token read at request time (login twice, the second token is sent).
- unauthorizedInterceptor: 401 → logout and rethrow; 401 from login → no logout; 403 → no logout.
- Guards: no session; expired session; each role on its own zone; each role on the other zone → own zone; guest guard with and without a session.
- `toProblem`: real ProblemDetail; status 0; string (HTML) body; null body.
- Login component: 401 shows `detail`; double submit sends one request; success navigates with `replaceUrl`; `?expired=1` shows the message.

### Consequences
- A reload keeps the session; a customer and an agent can be logged in side by side in two tabs.
- A stolen token is usable for up to one hour; this is mitigated only by preventing XSS.
- With a skewed client clock the client may consider a session valid a little longer; the 401 path covers it.
- After re-login the user starts from the zone home page, not from the page they were on.

### Alternatives considered
- Token in memory only; `localStorage`; httpOnly cookie (needs backend changes and CSRF protection, conflicts with the Bearer design).
- Decoding `exp` from the JWT on the client; a logout timer.
- One combined interceptor; class-based interceptors.
- Signal Forms.

## ADR-015: Customer zone

**Status:** Accepted
**Date:** 2026-10-26

### Context
The customer zone covers FR-03..FR-06 on top of the authentication from ADR-014. Response shapes were checked against `/v3/api-docs.yaml` and live responses:

    POST /api/customer/tickets            201, Location header, body CustomerTicketResponse
    GET  /api/customer/tickets?status=... 200, CustomerTicketSummary[]
    GET  /api/customer/tickets/{id}       200, CustomerTicketResponse
    POST /api/customer/tickets/{id}/messages  200, CustomerTicketResponse (updated status)

    CustomerTicketSummary:  id, subject, category, referenceNumber (string | null), status, createdAt, lastMessageAt
    CustomerTicketResponse: the same fields + messages[{ id, body, createdAt, authorRole }]

A validation failure returns 400 with `detail` "Validation failed." and a top-level `errors[{ field, message }]`. The order of `errors` is not guaranteed, and one field can have several entries. An unreadable body (for example an unknown category) returns 400 "Failed to read request" without `errors`. A non-numeric path id returns 400, a missing or foreign ticket 404 (BR-01).

The OpenAPI spec does not show every constraint: `subject` and `message` appear with `minLength: 0` and outside `required`, because the custom not-blank check is invisible to springdoc, and `errors` is not part of the `ProblemDetail` schema. Client rules therefore come from `docs/requirements.md`, not from the spec.

### Decision

#### 1. Routes
- Under the lazy `/client` tree (ADR-011): `''` → ticket list (FR-04), `tickets/new` → new ticket (FR-03), `tickets/:id` → thread (FR-05, FR-06). `tickets/new` is declared before `tickets/:id`. `ROLE_HOME` stays `/client`.
- A parent `ClientShell` component wraps the three pages and holds the zone navigation: a link to the list, the user's name and "Log out" (FR-02). The pages themselves have no logout button.
- After a successful create the app navigates to the new thread with `replaceUrl: true`, so Back returns to the list, not to the form.

#### 2. API service and types
- `features/client/customer-tickets-api.ts`: `providedIn: 'root'`, methods `list(status?)`, `get(id)`, `create(request)`, `postMessage(id, body)`, each returning an `Observable` from `HttpClient`. URLs are relative `/api/customer/...` (the auth interceptor adds the token).
- Types are written by hand from the OpenAPI spec in `features/client/customer-ticket.ts`. No client generator (operationIds are generated, see the backlog).
- Not `httpResource`: polling needs explicit control over when a request starts and which response is applied.

#### 3. Ticket id
- The `:id` route parameter is kept as a string and passed to the URL with `encodeURIComponent`, never converted to a number: a Java `long` can exceed `Number.MAX_SAFE_INTEGER`. The backend validates it.
- 400 and 404 from `GET /tickets/{id}` show the same "Ticket not found" view with a link to the list, and polling stops.

#### 4. Polling (NFR-06, ADR-010)
- Only on the thread page, every 4 seconds.
- The next poll is scheduled when the previous request has finished, so requests never overlap.
- While `document.visibilityState` is `hidden`, polling pauses; when the tab becomes visible, a request is sent at once.
- Polling stops when the page is destroyed (`DestroyRef`).
- Every response carries the whole thread and replaces the state.
- Every thread request (initial load, poll, reload, message post) gets an increasing sequence number. A response is applied only if its number is greater than the last applied one, so a poll that started before a message post cannot overwrite the newer thread.
- Poll errors: 401 is handled by the interceptor (ADR-014); 400/404 → "Ticket not found" and polling stops; network or 5xx → the last data stays, a non-blocking "Cannot refresh the conversation" note is shown, polling continues, and the note disappears after the next success.
- The ticket list is not polled; it loads when the page opens and when the filter changes.

#### 5. New ticket form (FR-03)
- Typed Reactive Forms (`nonNullable`), as in ADR-014 §7.
- Client validators follow FR-03, BR-11 and BR-18: `subject` required, not blank, at most 200 characters after trimming; `category` required, one of `GENERAL`, `POLICY`, `CLAIM`; `referenceNumber` optional, a blank value counts as absent, otherwise after trimming 1–50 characters of `[A-Za-z0-9-]`; `message` required, not blank, at most 4000 characters counted as typed. Lengths are JavaScript string lengths (UTF-16 code units), the same unit as Java's `String.length()`.
- Values are sent as typed; the server normalizes them.
- On 400 with `errors`: each entry is set on the control named by `field` as the error `server`; several messages for one field are joined with "; ". A server error is cleared when the user edits that control. Entries with an unknown `field`, and errors without `errors`, are shown as one message above the form from `toProblem().detail`.
- A `submitting` signal disables the button; the submit handler also ignores submits while a request is pending.

#### 6. Thread and composer (FR-05, FR-06)
- Messages in server order (NFR-05). Customer and agent messages are visually distinct by `authorRole`.
- Message bodies are rendered with interpolation and `white-space: pre-wrap`, never `innerHTML`.
- Composer: `body` required, not blank, at most 4000 characters; sent only with the button (Enter adds a new line). A `submitting` signal guards against double submits, as in §5.
- On a CLOSED ticket the composer stays available with the hint "Sending a message reopens this ticket."
- On success the text is cleared and the thread from the response is applied (§4). On any error the text stays and `toProblem().detail` is shown; on 409 the thread is also reloaded at once. 404 shows "Ticket not found" as in §3.
- No optimistic message before the response.

#### 7. Ticket list (FR-04)
- A single-choice status filter: All, Open, Answered, Customer replied, Closed. "All" sends no `status` parameter. The choice lives in a signal, not in the URL.
- Each row shows subject, category, reference number, status and last activity, and opens the thread. A "New ticket" action opens the form.

#### 8. States and display
- First load: a spinner. Polls never show a spinner.
- Empty list: "No tickets yet" with a "New ticket" action. Empty result with a filter: "No tickets with this status".
- A failed load: `toProblem().detail` with a "Retry" button.
- Dates use `DatePipe` in the browser's time zone. Status and category labels are defined in one place in the zone.

### Tests
Vitest with `provideHttpClientTesting` and `HttpTestingController`, `MemoryStorage` per TestBed; polling time through Vitest fake timers. Required cases:
- API service: each method calls the right URL and method; `list()` without a status sends no parameter; an id is URL-encoded and not converted to a number.
- Polling: the next request starts only after the previous one completed (a slow response produces no second request); a poll response that started before a message post and arrives after it is ignored; hidden tab → no requests, visible again → one request at once; destroy stops polling; 404 and 400 stop polling and show "Ticket not found"; a 5xx keeps the old thread, shows the note and polling continues.
- Form: field errors from `errors[]` land on their controls regardless of order; two messages for one field are both shown; an unknown field and a 400 without `errors` show the message above the form; a server error clears when its control changes; double submit sends one request; success navigates to the thread with `replaceUrl`; boundary lengths (200/201 after trimming, 4000/4001, reference number 50/51).
- Composer: double submit sends one request; error keeps the text; success clears it; 409 shows the detail and reloads; the CLOSED hint is shown only for CLOSED.
- List: "All" sends no parameter; a filter sends `?status=...`; empty and filtered-empty texts; error with Retry.

### Consequences
- A reloaded thread page reloads the conversation; the filter on the list resets to All after a reload.
- With polling every 4 seconds, an agent reply appears in up to about 4 seconds plus request time while the tab is visible.
- The server stays the only authority for validation (NFR-04); client checks only prevent obvious round trips.

### Alternatives considered
- Filter in the URL query string (survives a reload; deferred).
- `httpResource` or `rxResource` for loading and polling.
- A fixed `setInterval` (requests can overlap) instead of scheduling after completion.
- An optimistic message before the server response.
- A generated API client from the OpenAPI spec.