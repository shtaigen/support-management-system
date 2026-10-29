# Frontend CLAUDE.md

## Commands (run from `frontend/`)
There is no global Angular CLI: use npm scripts or `npx ng ...`, never bare `ng`.
- Dev server: `npm start`
- Build: `npm run build`
- Tests (Vitest, single run): `npx ng test --watch=false`. Plain `npm test` stays in watch mode.
- Generate: `npx ng generate ...`

## Conventions
- Standalone components only; no NgModules.
- `changeDetection: ChangeDetectionStrategy.OnPush` on every component.
- `inject()` for DI, never constructor injection.
- Signals for state (`signal`, `computed`); the app is zoneless (no `zone.js`), so UI updates
  must come from signals.
- Control flow `@if` / `@for` / `@switch`; never `*ngIf` / `*ngFor`.
- Functional guards (`CanMatchFn`), not class-based guards.
- Suffix-less file and class names: `login.ts` -> `class Login`, not `login.component.ts`.
- Formatting follows `.prettierrc` (single quotes, width 100).

## Roles and auth
- Roles are exactly `CUSTOMER` and `AGENT`, matching `users_role_ck` in the backend V1 migration.
  Do not invent other role names (no `ADMIN`, `STAFF`, `CLIENT`).
- Role-to-zone mapping (`/client`, `/staff`) lives only in `src/app/core/auth/user-role.ts`.
- Authentication follows ADR-014 in `docs/decisions.md`. Read it before changing anything in
  `core/auth/` or `core/http/`. If ADR-014 does not decide something, stop and ask.
- Invariants (details in ADR-014):
  - The session (`accessToken`, `expiresAt`, `user`) is stored in `sessionStorage` under the key
    `sms.session` and accessed only through `AuthService` and the `SESSION_STORAGE` token.
  - Never decode the JWT; expiry comes from `expiresAt`. Session validity is the method
    `hasValidSession()`, never a `computed`: signals do not track time.
  - The `Authorization` header is added only to relative `/api/` URLs, never to
    `/api/auth/login`.
  - Any 401 except from the login request ends the session through `logout({ expired: true })`;
    403 never logs out.
  - Guards return a `UrlTree`. They are UX only; the backend enforces access.

## Customer zone
- The customer zone follows ADR-015 in `docs/decisions.md`. Read it before changing anything in
  `features/client/`. If ADR-015 does not decide something, stop and ask.
- Validation rules come from `docs/requirements.md` (FR-03, BR-11, BR-18), not from the OpenAPI
  spec, which does not show every constraint.
- Never convert a ticket id from the URL to a number; pass it through as a string.

## Styling
The Angular Material M3 theme (azure primary, blue tertiary) is defined in `src/styles.scss`.
Use `--mat-sys-*` variables instead of hardcoded colors; change the theme only there.

## Tests
- `vitest-base.config.mts` turns off Vitest's `fsModuleCache` (stale imported modules under @angular/build 22.2.2, angular/angular-cli#34311). Do not turn it on. Remove the file and the `runnerConfig` option in `angular.json` after upgrading to @angular/build 22.2.3 or later.
- Before trusting mutation results, both tracers must fail: a failing `it()` in the spec and a top-level `throw` in the edited module. Every test run must print "Using Vitest configuration file".
- A test that depends on form state (submitted, touched, error state) goes through the real submit button, not a direct call of the handler.