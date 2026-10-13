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