# Working with AI: notes

How I use Claude Code in this project:
- Plan mode first. I review the plan and correct it before any file changes.
- I approve edits manually and read every diff. I stage and commit myself.
- Rules for the agent live in `CLAUDE.md` files (guidance) and `.claude/settings.json` (enforced permissions).

Concrete cases, in order.

## 2026-10-13: A .gitignore that ignored nothing
Claude Code ran `git check-ignore` and found that no rule worked: the lines had been pasted with three leading spaces, so `.env` was not ignored.
Lesson: verify ignore rules with `git check-ignore -v` instead of trusting the file; paste config without indentation.

## 2026-10-13: Scaffolding by hand, not by the agent
I ran `ng new` myself through `npx @angular/cli@22.2.2`. It is a deterministic generator with interactive prompts; the agent would only run the same command, slower and at a higher cost.
Lesson: AI for work with decisions; deterministic tools for deterministic work.

## 2026-10-13: The agent checked my assumptions against the source
My prompt asked for a `--typography` flag and a prebuilt theme. Before running `ng add`, Claude Code read the `@angular/material@22.2.2` schematic and found only `--project` and `--theme`; typography is always on in v22. It corrected the plan instead of guessing flags.
Lesson: ask the agent to verify tool options against the source or docs before running them.

## 2026-10-13: Plan review caught two design gaps
In the role-zones plan, lazy vs eager loading of `/login` was left undecided, and a logged-in user with the wrong role would be sent to `/login`. I fixed both before any code: eager login, and a wrong role redirects to the user's own zone, with tests for each branch.
Lesson: correcting a plan is cheaper than correcting a diff.

## 2026-10-13: A failing test the agent fixed itself
The guard spec failed to compile: in Angular 22 a `CanMatchFn` takes a third argument (the current route snapshot). The agent ran the tests, saw the error and fixed the spec.
Lesson: "done" means build and tests passed, not "the code looks right".

## 2026-10-13: CLAUDE.md drafted by the agent, curated by me
The agent added repo-verified facts I had not listed: the backend does not load `.env`, `npm test` stays in watch mode, the app is zoneless. My review added two rules: the agent never stages files or switches branches, and emails are lowercased with `Locale.ROOT`.

## 2026-10-13: Guidance vs enforcement
`CLAUDE.md` shapes what the agent tries to do; `.claude/settings.json` decides what Claude Code allows. On Windows the agent runs commands through the PowerShell tool, so rules must be written as `PowerShell(...)`; `Bash(...)` rules alone would not match. Ask rules still prompt in auto mode.
Limit: rules match command text and are not a security boundary (`git -C . push` is not matched by `git push *`); real isolation needs a sandbox.

## 2026-10-14: The agent followed the project rules without reminders
While running the backend build locally, the agent did not read `.env` (denied in `.claude/settings.json`) and planned to report a credentials mismatch instead of working around it. Starting the database used the allowed `docker compose up`; the build command asked for approval.

## 2026-10-14: A green local build that proved less than it seemed
Locally, Flyway reported "No migration necessary": the Docker volume already had V1 applied, so the run did not check V1 on an empty database. The agent pointed this out. CI starts every run with an empty Postgres service container, so the first green CI run was the first real check of V1 from scratch.
Lesson: "works on my machine" can hide state; CI's clean environment is part of the test.

## 2026-10-14: The spec described the old system, not ours
While deriving docs/requirements.md from an earlier implementation of the same domain, the agent analysed the source accurately, but its first plan listed choices already fixed in V1 and the ADRs as open questions and was about to publish defects of the old code as findings. After I pointed it to docs/decisions.md and V1 as the source of truth, required a Source field for every requirement and asked for defects to be restated as positive requirements, the second plan specified our system with traceable IDs.
Lesson: an agent reverse-engineering code reproduces what it reads; project decisions must be in its context explicitly.

## 2026-10-15: JPA entities and DB default timestamps
While planning entities for V1, Claude Code pointed out that Hibernate lists every mapped column in its INSERT, so a DB DEFAULT now() on created_at would never fire and the NOT NULL constraint would fail. We set timestamps in the entity constructors, refresh updated_at via @PreUpdate, and truncate to microseconds to match timestamptz. DB defaults stay only as a safety net for manual SQL and seeds.
Lesson: With ddl-auto validate the schema is the contract, but DB defaults are not part of what Hibernate writes; decide explicitly which side owns each generated value.

## 2026-10-15: Locale-safe normalization test
Claude Code noticed that its own test for email normalization did not actually exercise a non-default locale, and rewrote it to run under a Turkish default locale, where "I".toLowerCase() yields a dotless i. The test now proves that Locale.ROOT is required, instead of passing trivially.
Lesson: A test that passes on the developer's machine regardless of the fix proves nothing; make the test fail without the fix first.

## 2026-10-15: Mockito agent and a Windows path with spaces
Passing Mockito as a Java agent via surefire argLine crashed the forked JVM locally: the Maven repository lives under "C:\Users\O M E N\.m2", and the unquoted -javaagent path was split at the spaces. Quoting the path fixed it; CI on Linux would never have shown the problem.
Lesson: Build configuration that embeds file paths must be tested on the developer machine, not only in CI; quote paths in argLine.

## 2026-10-15: Stale classes after moving an entity
After Message moved from the message package to ticket, Claude Code noticed that target/classes still contained the old message/Message.class. Incremental builds do not remove classes of deleted sources, so a local build could fail with a duplicate entity name or, worse, pass against a class that no longer exists. A clean build removed the risk; CI was never affected because it builds from a fresh checkout.
Lesson: After moving or deleting classes, run a clean build before trusting a local green result.

## 2026-10-19: Demo seed — agent asked instead of guessing
The prompt told the agent to stop if the ADRs did not decide the seeding mechanism. It did, and offered three options with trade-offs (Java runner under a profile, Flyway V2, profile-only Flyway location). Choosing the runner kept Flyway schema-only and kept demo passwords out of prod. Plan review also caught a naming clash: a default repository method cannot delegate to a derived query with the same name.
Lesson: an explicit "stop and ask if the ADRs don't decide" turns a hidden architectural choice into a reviewed decision.

## 2026-10-19: Login endpoint — limits that look like details
Plan review for the login endpoint surfaced three non-obvious constraints. BCrypt accepts at most 72 bytes, not characters, so @Size alone lets a 25-character password of multi-byte symbols through and the encoder then throws a 500; a small UTF-8 byte validator turns it into a 400. A read-only transaction around login would hold a pooled connection during the ~100 ms BCrypt check, so the transaction was removed. JWT timestamps are whole seconds, so the issue instant is truncated once and reused for iat, exp and expiresAt.
Lesson: review a plan for resource and unit boundaries (bytes vs chars, seconds vs nanos, connection lifetime), not only for business logic.

## 2026-10-19: Resource server — 401 vs 403 and a framework surprise
The plan treated a valid token without a known role as 403, not 401: the caller is authenticated, and logging in again would not help. The default bearer entry point puts the rejection reason into WWW-Authenticate, so it was replaced to keep every rejected token indistinguishable. Security rules were tested without probe controllers: a wrong zone gives 403, a missing path in the own zone gives 404, which proves the request passed security. During implementation Spring Security 7 turned out to add a FACTOR_BEARER authority next to the roles (multi-factor support); role checks were unaffected, but a strict authorities assertion would have failed.
Lesson: assert only what the code under test controls; framework upgrades add things around it.

## 2026-10-20: Stale ADRs mislead the agent
Before starting the ticket endpoints, ADR-008 still described a `message/` package and an `onCustomerMessage()` method that no longer exist; messages had moved into the `Ticket` aggregate behind `postMessage()`. Every Claude Code prompt names docs/decisions.md as the source of truth, so the agent would have followed the outdated ADR and could have added a second message write path, breaking BR-17. ADR-008 was updated before the next task, and OQ-01 was resolved in requirements.md so FR-08 has a defined shape before implementation.
Lesson: documents that the agent treats as the source of truth must be brought up to date before the task, not after it; an outdated ADR is worse than a missing one.

## 2026-10-20: Agent asked instead of choosing
Before planning 5a, Claude Code stopped on two gaps the ADRs did not cover: how an unknown enum value should fail, and what to do when the JWT subject has no matching user. Both answers were refined (explicit role check instead of relying on the domain IllegalArgumentException).
Lesson: the "stop and ask if the docs do not decide" rule works; review the offered options against the checklist, not just pick the recommended one.

## 2026-10-20: Unhandled exceptions bypass Problem Details
The 5a plan showed that an exception not covered by ApiExceptionHandler would go to /error and Boot's BasicErrorController, giving a non-ProblemDetail body, and the ERROR dispatch also passes the security chain. A catch-all handler with a fixed message closes both, as long as security exceptions are rethrown.
Lesson: "no new handlers" in a prompt is a default, not a law; ask the agent to show the actual response path for 500 and for path-variable type errors before accepting it.

## 2026-10-20: Test transactions hide missing service transactions
Reviewing CustomerTicketService showed that every integration test runs inside its own @Transactional, so the tests would still pass if the service's @Transactional were ignored (for example on a method the proxy cannot intercept). Spring 6 does apply @Transactional to package-private methods of class-based proxies, so 5a is correct, but the tests do not prove it.
Lesson: rollback-per-test proves behaviour, not transaction boundaries; when a boundary matters (BR-16), cover it with one test that runs without a test transaction.

## 2026-10-20: Pin timestamp round-trips outside the persistence context
A test-level transaction lets a POST response and a following GET read the same in-memory entity, so a mismatch between Java and column precision would stay hidden. The entities already truncate to microseconds; flushing and clearing between the requests turns that into a tested guarantee.
Lesson: When comparing a write response with a later read, clear the persistence context first.

## 2026-10-20: Auto-flush follows query spaces
In the conflict test the thread query touched only messages and users, so Hibernate did not flush the dirty ticket before it; the versioned UPDATE ran at commit and the optimistic-locking failure came from the transaction manager, not from a repository call. A handler or test that assumes the failure happens at a specific repository call can be wrong.
Lesson: Know where the flush happens before deciding where an optimistic-locking failure surfaces.

## 2026-10-20: Reconcile agent reports with hard numbers
The agent reported 23 new controller test runs, but the build total grew by only 20. Asked to reconcile, it counted with git diff and found 18; no test had been lost. A mutation check (removing @Transactional from the service) then confirmed that the conflict test fails with 500 instead of 409.
Lesson: Check an agent's summary against build totals and diffs, and prove a test's purpose by breaking the code it guards.

## 2026-10-21: A mutation check showed why the reply flushes
The agent reply (FR-10) returns version and updatedAt, so the service calls tickets.flush() before building the response. The conflict test does not need that line, because the optimistic-locking failure still surfaces at commit. Only a mutation check showed what the line protects: without it the POST response had an updatedAt a few milliseconds earlier than the following GET, because @PreUpdate ran again at the final flush.
Lesson: For every line added to avoid a subtle pitfall, ask which test fails without it; if none does, the line will be deleted later as dead code.