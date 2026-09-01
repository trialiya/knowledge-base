# CLAUDE.md

Knowledge Base — an AI assistant for documentation and codebase analysis. One
Gradle multi-project: a Spring Boot backend (`:backend`, Java 25, Spring AI,
PostgreSQL 17 + pgvector, H2 for local runs and tests) and a React 19 frontend
(`:frontend`), bundled into the backend JAR. Product docs (Russian) are in
`docs/`.

## Architecture in brief, and where to read more

One SPA over one API. The frontend has four sections — chat, knowledge base,
files, settings/admin — all rendered through the shared `WorkspaceLayout`; the
URL is the navigation state and `navigation/useAppNavigation.js` is its only
writer. Most controllers are a plain `controller → service → Spring Data JDBC`
stack (documents, attachments, phrases, …); chat is the one path where the
service layer also drives Spring AI, which calls back into services through
`@Tool` functions (`functions/`) to read and write the knowledge base and the
repo. Chat answers are background runs on virtual threads, streamed over one
SSE event channel per chat, so an answer survives a page reload; search is
hybrid — SQL keyword plus pgvector semantic.
`service/chat` splits into sub-packages with a one-way dependency direction
(`event` ← `runtime` ← `memory` ← `run`); `event/` and `runtime/` carry a
`package-info.java` describing what belongs there — `memory/` and `run/` have
one too, but it is only the `@NullMarked` boilerplate, not a description.

For anything deeper, start with `docs/проект/` rather than a cold read of the
tree. The docs occasionally lag the code — verify load-bearing details in the
source — but as orientation they are the fastest way in:

| Question | Read in `docs/проект/` |
| --- | --- |
| Layers, stack, key decisions | `архитектура.md` |
| Chat runs, SSE, advisors, memory | `обзор-чат-системы.md` |
| Frontend structure and state | `фронтенд-обзор-архитектуры.md` |
| Endpoints / config keys / entities | `api-reference.md` · `конфигурация.md` · `модели-данных/` |
| The `@Tool` catalogue | `ai-инструменты.md` |
| Search internals | `архитектура-и-реализация-поиска.md` |

## Running checks

**Never assemble a `gradle` command by hand — every check goes through
`run/test.sh`** (Windows: `run\test.bat` / `run\test.ps1`). It decides the
awkward parts itself: system Gradle vs `./gradlew`, the Java 21 fallback,
starting `dockerd` for Testcontainers.

```bash
./run/test.sh                  # unit + front — the fast pair, no Docker
./run/test.sh lint             # PMD + SpotBugs over backend main sources
./run/test.sh pre-pr           # format + lint + back + build — the gate before a PR
./run/test.sh smoke            # build the JAR and drive the UI with Chromium
./run/test.sh harness          # screenshot one component against a fixture, no backend
./run/test.sh harness-image    # the same in the image the baselines were taken in
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
  PMD + SpotBugs · `:backend:test` · `build`. All of it runs in the web sandbox
  too, IT tests included.
- **PMD and SpotBugs gate the backend** (main sources only, like NullAway), but
  deliberately *not* through `check` — `build` does not run them, `./run/test.sh
  lint`, `pre-pr` and CI do. The rule selection and every exclusion, each with
  its reason, live in `config/pmd/ruleset.xml` and
  `config/spotbugs/exclude.xml` — fix the finding first; suppress in place
  (`@SuppressWarnings("PMD.Rule")` plus a comment, or an addressed `<Match>`)
  only when the rule is wrong about that spot, and turn a rule off wholesale
  only when it is wrong about the project.
- **Dependency locking is on.** After changing dependencies run
  `./gradlew resolveAndLockAll --write-locks`.
- **A schema change is four edits, not one.** Write the migration for both
  `db/migration` (Postgres) and `db/migration-h2`, then update
  `db/sample-data.sql` and `SampleDataFixtureTest` to match.
- **Keep documentation in sync.** When adding or modifying functionality,
  update the relevant documentation in `docs/` as part of the same change.
- **A comment says how the code works now, never how it got that way.** No
  "previously / раньше / used to be", no account of what was renamed, merged or
  deleted — `git log` holds that, and a header that narrates its own
  refactorings only grows. Keep the *rule* the history left behind, drop the
  chronicle: "do not call `renderValue` here, it wipes the browser's undo stack"
  earns its lines; the story of which change broke it and which change fixed it
  does not. Link an issue or a PR when it still carries something the reader
  needs — an upstream bug, a decision with an argument behind it — not as a
  timeline of this file.
- **Don't restate what the reader can already see.** Config defaults copied
  into a Javadoc, an endpoint list next to the `@GetMapping`s, a prop list
  mirroring the destructuring three lines below — all of it drifts, and a
  confidently wrong comment costs more than a missing one. Name the source of
  truth instead, and spend the comment on what the code cannot show: the why,
  the invariant, the trap.
- **Keep files focused — measured in lines of code.** Comments and blank lines
  don't count (`grep -cvE '^\s*(//|/?\*|$)' <file>` is the honest number; on
  the backend Javadoc plus AOSP formatting make raw `wc -l` about 60% bigger
  than the code it holds). A frontend file nearing **~300** code lines — or
  holding 2+ exported components — and a backend Java file nearing **~500**
  are due for a split. Existing offenders are handled by "migrate on touch"
  below: split a file the task already has open, don't hunt the rest down.
- **Migrate on touch.** The codebase is mid-migration onto shared components.
  When you edit a file that still carries a legacy pattern — its own modal
  chrome, its own button classes, a panel-local copy of a shared concern —
  migrate that file as part of the change, but do not fan out into files the
  task doesn't touch. One PR = the task plus the files it already touched.
- **New user-facing strings go through i18n** (`en` + `ru` locale files), never
  hardcoded.
- **Never subscribe to PR activity.** Do not call `subscribe_pr_activity` /
  `mcp__github__subscribe_pr_activity`, do not enable auto-fix on a pull
  request, and do not offer or ask to watch/monitor/babysit a PR — not after
  opening one, and not when a user asks for it. If auto-fix or PR monitoring
  comes up, say it's disabled for this repository instead of subscribing.

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
