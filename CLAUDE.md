# CLAUDE.md

Support ticket system: Spring Boot backend (`backend/`) + Angular frontend (`frontend/`) in one
monorepo. Customers open tickets and chat with staff; staff manage tickets by status and assignee.
A legacy Express/TypeScript version lives outside this repo and is used only as a functional
spec. Never copy its code or structure, and never reference its path from this repo.

See `backend/CLAUDE.md` and `frontend/CLAUDE.md` for stack-specific rules.

## Environment
- Windows 11. The agent's shell is PowerShell: use `.\mvnw.cmd`, not `./mvnw`; no `&&`.
- The user's home path contains spaces (`C:\Users\O M E N\...`): always quote paths.

## Local database
- `docker compose up -d` from the repo root starts Postgres 18 on `127.0.0.1:5432`.
- Credentials come from `.env` (copy from `.env.example`). Never read, print or commit `.env`.
- The backend does not load `.env`: `application.yaml` falls back to defaults that match
  `.env.example`. If `.env` values differ, export them to the backend process too.
- A locally installed Postgres on port 5432 will conflict with the container.

## Git
- Never run `git commit`, `git push`, `git reset` or any other history-changing command.
- Never stage (`git add`), stash, or switch/create branches either.
  The user stages and commits manually. Read-only git (status, diff, log) is fine.
- When asked for a commit message, use Conventional Commits with one of the scopes
  `backend`, `frontend`, `ci`, `docs`, e.g. `feat(backend): add ticket entity`.
  Changes to repo-root files (`.gitignore`, `CLAUDE.md`, docs) may omit the scope,
  e.g. `docs: add CLAUDE.md files` or `chore: update .gitignore`.

## Language
All code, comments, docs and commit messages are in English.

## Workflow
- Plan first; get agreement before larger changes.
- Work in small steps; one concern per change.
- Before reporting done, run the build and tests of every touched module (see the module
  CLAUDE.md files) and report failures as they are.
- Do not add dependencies or tooling not asked for; ask first.
