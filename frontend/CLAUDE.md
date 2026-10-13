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
- `AuthService` is a temporary stub: the role is kept in memory and lost on reload. Do not
  build features that rely on it persisting; real auth will replace it.

## Styling
The Angular Material M3 theme (azure primary, blue tertiary) is defined in `src/styles.scss`.
Use `--mat-sys-*` variables instead of hardcoded colors; change the theme only there.
