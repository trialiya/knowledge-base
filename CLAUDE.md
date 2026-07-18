# CLAUDE.md

Guidance for Claude Code (and contributors) working in this repository.

## Project overview

Knowledge Base — AI assistant for documentation and codebase analysis. Single
Gradle multi-project: Spring Boot backend (`:backend`, Java 25, Spring AI,
PostgreSQL 17 + pgvector / H2) + React 19 frontend (`:frontend`), bundled into
the backend JAR. Full docs (Russian) under `docs/`.

## Common commands

```bash
./gradlew build                 # Full build (frontend bundled into backend JAR)
./gradlew :backend:bootRun      # Run backend (dev) on :8080
./gradlew :frontend:yarnServe   # Frontend dev server on :3000 (proxies to :8080)
./gradlew :backend:test         # Backend tests (JUnit 5)
./gradlew :frontend:yarnTest    # Frontend tests (Jest)
./gradlew spotlessApply         # Format backend (Google Java Format, AOSP)
```

`*IT` tests use Testcontainers and need Docker; `*Test` (unit) tests don't.

## Testing on JDK 21 (no JDK 25 available)

The backend toolchain targets Java 25. On environments with only JDK 21 (some CI
runners, the web sandbox) the build fails to resolve the toolchain. Apply the
`gradle/java21.gradle` init script to retarget to 21 and enable preview features
(`ToolTranslationsTest` uses unnamed variables `_`, a Java 21 preview finalized
in 22–25):

```bash
./gradlew :backend:test --init-script gradle/java21.gradle --no-configuration-cache
```

`--no-configuration-cache` is required. This is a local/CI workaround only — keep
`backend/build.gradle` on Java 25.

## Building & testing in the Claude Code web sandbox

**`./gradlew` does not work here** (the gradle-9.6.1 download is blocked) —
use the system Gradle at `/opt/gradle/bin/gradle`.

**For `*IT` tests start `dockerd` first:**

```bash
sudo dockerd > /tmp/dockerd.log 2>&1 &
until docker ps > /dev/null 2>&1; do sleep 1; done
```

Then (init script needed because only JDK 21 is available, see above):

```bash
# Unit tests only (no Docker needed)
/opt/gradle/bin/gradle :backend:test --init-script gradle/java21.gradle --no-configuration-cache --tests "*Test"

# All backend tests incl. *IT (dockerd must be running)
/opt/gradle/bin/gradle :backend:test --init-script gradle/java21.gradle --no-configuration-cache

# Full build (frontend Node/yarn downloads work; Jest runs too)
/opt/gradle/bin/gradle build --init-script gradle/java21.gradle --no-configuration-cache
```

`--no-configuration-cache` is required with the init script. Maven Central,
plugins.gradle.org, nodejs.org and Docker Hub are all reachable; only the
Gradle distribution download is blocked.

## Visually validating the frontend in the web sandbox (Playwright)

Chromium and Playwright are pre-installed (no `playwright install`). Don't use
`yarn start` — the dev server doesn't work here; boot the backend jar (H2
profile, dummy AI env vars) and drive it with Chromium instead.

`scripts/playwright-smoke.js` is a working, runnable example — boots the jar,
polls `/actuator/health`, logs in via HTTP Basic (`admin`/`admin`), waits for
the SPA to mount, and screenshots it. See its header comment for the details
(incl. the `LANG=C.utf8` locale gotcha — the sandbox has no locale configured,
so a bare JVM defaults to ASCII and `GitService` throws on non-ASCII repo
paths). Build the jar first, then run it:

```bash
/opt/gradle/bin/gradle :backend:bootJar -x :frontend:yarnTest \
  --init-script gradle/java21.gradle --no-configuration-cache

NODE_PATH=/opt/node22/lib/node_modules node scripts/playwright-smoke.js
```

Copy its `chromium.launch()`/env-var setup for ad hoc checks beyond a screenshot.

## Тестовые данные для H2 (`db/sample-data.sql`)

`backend/src/test/resources/db/sample-data.sql` is a ready-made H2 dataset (a real
captured chat conversation plus documents, attachments and tool calls) for manual
QA and as a `@Sql`-loadable fixture in tests. Targets the `db/migration-h2` schema
only — do **not** run it against real Postgres, the array/vector column types
differ. Full contents and rationale are in the file's own header comment.

`SampleDataFixtureTest` is the worked usage example (`@Sql` on an H2
`@DataJdbcTest`, same pattern as `DocumentServiceUnitTest`) and also the
regression test keeping the fixture in sync with `db/migration-h2` — run it
after touching either.

## Frontend conventions (`frontend/src`)

Target state and rules for anyone touching frontend code. The codebase is
mid-migration — follow these for all new code, and migrate existing code
**incrementally, as files are touched** (see below), not in big-bang rewrites.

### Migrate-on-touch policy

When editing a file that still uses a legacy pattern (own modal chrome, own
button classes, panel-local copy of a shared concern), migrate that file to the
shared pattern as part of the change — but do not fan out into files the task
doesn't touch. One PR = the task + migration of the files it touched.

### Modals

- Use the shared `<ModalShell>` from `components/common/` for every dialog
  (once it exists — until then, copy `ConfirmModal`'s structure). It owns:
  `createPortal` to `document.body`, overlay + backdrop-close, Escape via
  `useEscape`, `role="dialog"`/`"alertdialog"` + `aria-modal`, and the shared
  modal CSS. Components supply only header/body/footer content.
- Do not introduce new `*-overlay` classes or per-modal overlay divs
  (`modal-overlay`, `error-modal-overlay`, `tcd-overlay`, `fcd-overlay`,
  `fs-editor-overlay`, … are legacy — fold into ModalShell when touched).
- Backdrop close is `onMouseDown` (not `onClick`), so text selection that ends
  outside the modal doesn't dismiss it.

### Buttons

- Use the shared button classes from `components/common/`: `btn`,
  `btn--primary`, `btn--ghost`, `btn--danger`, `btn--sm`, and `icon-btn` for
  icon-only buttons (modeled on settings' `set-btn` family). Don't add new
  panel-local button classes (`set-btn`, `jira-modal__btn`, `detail-icon-btn`,
  `new-chat-button`, … are legacy).

### CSS

- One naming scheme: BEM (`block__element--modifier`), lowercase-hyphenated
  block names. No new abbreviated prefixes (`tcd-`, `fcd-`, `set-`).
- File layout: shared styles live next to their component in `common/`;
  panel styles go in `<panel>/styles/<topic>.css` (one topic per file, like
  `chatPanel/styles/` and `knowledgeBasePanel/styles/`) — don't grow
  monolithic per-panel files (`chatWindow.css` at 1100+ lines is the
  anti-example, being split).
- CSS is plain (no modules/preprocessor); classes are global — prefix with the
  block name to avoid collisions, and never reference another panel's classes
  (shared chrome belongs in `common/`).

### Components & hooks

- Components render; hooks own state/API orchestration; pure logic goes in
  plain `.js` modules next to the feature (`treeOps.js`, `fileChips.js`).
- Keep files focused: a file approaching ~300 lines or holding 2+ exported
  components is due for a split. Big-file precedents being dismantled:
  `ChatWindow.jsx`, `useKnowledgeBase.js`, `DocLinkTooltip.jsx`,
  `FileChipInput.jsx`.
- Reuse the shared hooks before writing new plumbing: `useSearchDropdown`
  (search-button → dropdown widgets), `useEscape`, `useDocPreview`/
  `useFilePreview` (module-cache preview pattern).
- Async effects must be cancellation-aware (`cancelled` flag or AbortSignal in
  cleanup), matching the existing preview hooks.
- The two trees are intentionally separate: `knowledgeBasePanel/TreeNode`
  (editable: drag-drop, pagination) vs `filesPanel/FileTreeNode` (read-only).
  Do not unify them.
- New user-facing strings go through i18n (`en` + `ru` locale files), never
  hardcoded.

## Before a PR

`./gradlew spotlessCheck` · `./gradlew :backend:test` · `./gradlew build`
(add `--init-script gradle/java21.gradle --no-configuration-cache` on JDK 21;
in the web sandbox use `/opt/gradle/bin/gradle` and start `dockerd` first —
all three checks can run there, IT tests included).
Dependency locking is on — after changing deps run
`./gradlew resolveAndLockAll --write-locks`.
