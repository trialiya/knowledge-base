# CLAUDE.md

Knowledge Base — an AI assistant for documentation and codebase analysis. One
Gradle multi-project: a Spring Boot backend (`:backend`, Java 25, Spring AI,
PostgreSQL 17 + pgvector, H2 for local runs and tests) and a React 19 frontend
(`:frontend`), bundled into the backend JAR. Product docs (Russian) are in
`docs/`.

## Running checks

**Never assemble a `gradle` command by hand — every check goes through
`run/test.sh`** (Windows: `run\test.bat` / `run\test.ps1`). It decides the
awkward parts itself: system Gradle vs `./gradlew`, the JDK 21 init script,
starting `dockerd` for Testcontainers.

```bash
./run/test.sh                  # unit + front — the fast pair, no Docker
./run/test.sh pre-pr           # format + back + build — the gate before a PR
./run/test.sh smoke            # build the JAR and drive the UI with Chromium
./run/test.sh unit -- --tests '*ToolTranslationsTest'   # after -- goes to Gradle
```

The full suite list, the `--` passthrough rules and `KB_JAVA21` are in the
script's own header — read it there rather than expecting this file to mirror
it. Windows has no `smoke` and no `--` passthrough, and needs Docker already
running.

The wrapper deliberately does not cover:

```bash
./gradlew :backend:bootRun     # dev backend on :8080 (run/run.sh h2 runs the JAR)
./gradlew :frontend:yarnServe  # Vite dev server on :3000, proxies /api to :8080
./gradlew spotlessApply        # format the backend (Google Java Format, AOSP)
```

`*IT` suites use Testcontainers and need Docker; `*Test` (unit) suites don't.

## Rules that apply everywhere

- **Before a pull request run `./run/test.sh pre-pr`** — `spotlessCheck` ·
  `:backend:test` · `build`. All three run in the web sandbox too, IT tests
  included.
- **Dependency locking is on.** After changing dependencies run
  `./gradlew resolveAndLockAll --write-locks`.
- **A schema change is four edits, not one.** Write the migration for both
  `db/migration` (Postgres) and `db/migration-h2`, then update
  `db/sample-data.sql` and `SampleDataFixtureTest` to match.
- **Migrate on touch.** The codebase is mid-migration onto shared components.
  When you edit a file that still carries a legacy pattern — its own modal
  chrome, its own button classes, a panel-local copy of a shared concern —
  migrate that file as part of the change, but do not fan out into files the
  task doesn't touch. One PR = the task plus the files it already touched.
- **New user-facing strings go through i18n** (`en` + `ru` locale files), never
  hardcoded.

## Conventions live next to the code they govern

This file stays short on purpose. The detailed conventions load on their own
when a file they govern is opened. If you are about to **create** files in one
of these areas without opening an existing one first, read the rule yourself:

| Working on | Read |
| --- | --- |
| `backend/` — chat persistence, tool calls, migrations, test fixtures | `.claude/rules/backend-data.md` |
| `frontend/src` `.js`/`.jsx` — layout, URL scheme, panels, modals, hooks | `.claude/rules/frontend-ui.md` |
| `frontend/src/**/*.css` — styles | `.claude/rules/frontend-css.md` |
| Running Gradle by hand, or a build failing on the toolchain / a blocked download / a Testcontainers pull | the `build-environment` skill |
| Checking how the UI actually looks, not just that tests are green | the `frontend-visual-check` skill |
