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

## Deliberately absent
Lombok and Testcontainers are not in the project on purpose.
Do not add them (or alternatives) without asking first.

## Spring Security
Present: `spring-security-crypto` (the shared `PasswordEncoder` bean, BCrypt) and
`spring-security-oauth2-jose` (`NimbusJwtEncoder`, used only to issue tokens at
`POST /api/auth/login`). Neither activates Spring Boot security auto-configuration: in Boot 4 it
lives in the `spring-boot-security*` modules that only the starters bring in. `NoWebSecurityYetTest`
checks this. The security starter, resource server, `SecurityFilterChain` and `JwtDecoder` bean are
added in step 4c (ADR-009). Do not add other security dependencies without asking first.

JWT signing key `app.jwt.secret`: Base64, at least 32 random bytes after decoding. There is no
default in `application.yaml`, and startup fails if it is missing or too short.
- Prod and other runs: set `APP_JWT_SECRET`.
- Demo profile: `application-demo.yaml` has a dev-only key.
- Tests and CI: `src/test/resources/config/application.yaml` has a test-only key. It sits under
  `config/` on purpose: a test-root `application.yaml` would replace the main one.
- Generate a key: `openssl rand -base64 32`.

Never log passwords, hashes or tokens (NFR-02). Request/response records that hold them mask
`toString()`.
