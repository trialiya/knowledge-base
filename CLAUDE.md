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

A SessionStart hook (`.claude/hooks/session-start.sh`, web-only) already sets
`LANG=C.utf8`, exports `GRADLE=/opt/gradle/bin/gradle`, and pre-compiles
backend main+test classes with the JDK 21 init script — so the dependency
cache is warm and `spotlessCheck`/unit tests start fast without extra setup.

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
the SPA to mount, and screenshots it. By default it also seeds `db/sample-data.sql`
into a disposable `local-db/h2-smoke` file first (never your real `local-db/h2`),
so the screenshot shows real chat/document content instead of an empty app — pass
`--no-seed` to skip that. See its header comment for the details (incl. the
`LANG=C.utf8` locale gotcha — the sandbox has no locale configured, so a bare JVM
defaults to ASCII and `GitService` throws on non-ASCII repo paths). Build the jar
first, then run it:

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

## Tool-call storage architecture (backend)

The most non-obvious part of the backend — read this before touching
`ChatMemoryService`, chat persistence, or tool-call UI endpoints.

- **No dedicated tool-call table.** Protocol tool data (the assistant's tool
  calls and the TOOL responses) lives in `chat_message.tool_data` (JSON, see
  `ToolData`/`ToolDataToJsonConverter`), alongside the message it belongs to.
  UI-only metadata (names, arg gists, statuses shown in chat) lives in the
  message `meta` as `ToolInvocationMeta`/`ToolInvocation` — never mix the two:
  `tool_data` is what the LLM protocol needs to replay history, `meta` is what
  the frontend renders.
- **`callId` is the join key.** Every call/response carries the protocol
  `callId`. `tool_call_index` (see `ToolCallIndexEntity`) maps
  `conversationId + callId` → the `chat_message` ids holding the full details
  (issuing ASSISTANT segment, and the TOOL response row once it arrives).
  `ChatMemoryService.findToolCallDetail` is a plain lookup through it — do not
  reintroduce positional/offset arithmetic over message history.
- **The index is filled at persist time** (`ChatMemoryService.saveAll`), not by
  a background job. Keep it in sync when changing how messages are saved.
- **`ToolCallIdBackfillRunner` is one-shot legacy support** for data recorded
  before `tool_call_index`/`callId` existed. Off by default
  (`kb.backfill.tool-call-ids=true` enables one run; idempotent). Once all
  environments have been backfilled it — and
  `ChatMemoryService.backfillToolCallIds` — should be deleted, not extended.
- Migrations for this live in both `db/migration` (Postgres) and
  `db/migration-h2`; schema changes must update both, plus
  `db/sample-data.sql` + `SampleDataFixtureTest`.

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

- Use the shared `<ModalShell>` from `components/common/` for every dialog. It owns:
  `createPortal` to `document.body`, overlay + backdrop-close, Escape via
  `useEscape`, `role="dialog"`/`"alertdialog"` + `aria-modal`, and the shared
  modal CSS. Components supply only header/body/footer content.
- Do not introduce new `*-overlay` classes or per-modal overlay divs — the
  legacy ones (`modal-overlay`, `tcd-overlay`, …) have been folded into
  ModalShell; keep it that way.
- Backdrop close is `onMouseDown` (not `onClick`), so text selection that ends
  outside the modal doesn't dismiss it.

### Buttons

- Use the shared button classes from `components/common/`: `btn`,
  `btn--primary`, `btn--ghost`, `btn--danger`, `btn--sm`, and `icon-btn` for
  icon-only buttons (modeled on settings' `set-btn` family). Don't add new
  panel-local button classes (`set-btn`, `detail-icon-btn`,
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
  components is due for a split. Big-file precedents still being dismantled
  (keep this list current as they shrink): `ChatWindow.jsx` (~900 lines, worst
  offender — untouched), `useKnowledgeBase.js` (~700), `icons/index.jsx`
  (~640), and `DocLinkTooltip.jsx` (~340). `FileChipInput.jsx` was decomposed
  into `ChipEditor.jsx` + `RichTextEditor.jsx`/`useChipPicker.js`/
  `useChipPreview.js`/`chipTriggers.js` and is off this list.
- Reuse the shared hooks before writing new plumbing: `useSearchDropdown`
  (search-button → dropdown widgets), `useEscape`, `useDocPreview`/
  `useFilePreview` (both built on `usePreviewCache` — the module-cache preview
  pattern; new preview kinds should reuse it too).
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
