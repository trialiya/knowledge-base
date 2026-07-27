# CLAUDE.md

Guidance for Claude Code (and contributors) working in this repository.

## Project overview

Knowledge Base — AI assistant for documentation and codebase analysis. Single
Gradle multi-project: Spring Boot backend (`:backend`, Java 25, Spring AI,
PostgreSQL 17 + pgvector / H2) + React 19 frontend (`:frontend`), bundled into
the backend JAR. Full docs (Russian) under `docs/`.

## Common commands

**Every check goes through `run/test.sh`** (Windows: `run\test.bat` /
`run\test.ps1`). It decides the awkward parts itself — system Gradle vs
`./gradlew`, the JDK 21 init script, starting `dockerd` for Testcontainers,
`CI=true` for Jest — so none of that has to be retyped:

```bash
./run/test.sh                   # unit + front — the fast pair, no Docker
./run/test.sh pre-pr            # format + back + build, before a pull request
./run/test.sh smoke             # build the JAR + drive the UI with Chromium
./run/test.sh unit -- --tests '*ToolTranslationsTest'   # after -- goes to Gradle
```

The full suite list, the `--` passthrough rules and `KB_JAVA21` are in the
script's own header — that copy sits next to the code and cannot drift from it,
so read it there rather than expecting this file to mirror it. Windows lacks
`smoke` and the `--` passthrough, and needs Docker already running.

What the wrapper deliberately does not cover:

```bash
./gradlew :backend:bootRun      # backend (dev) on :8080; run/run.sh h2 runs the built JAR
./gradlew :frontend:yarnServe   # frontend dev server on :3000 (proxies to :8080)
./gradlew spotlessApply         # format backend (Google Java Format, AOSP)
```

`*IT` tests use Testcontainers and need Docker; `*Test` (unit) tests don't.
Raw Gradle invocations are for debugging the build itself — the environment
flags they need are in "Environment quirks" at the end of this file.

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

### Layout (оболочка разделов)

- Every section (chat, knowledge base, files) renders through the shared
  `<WorkspaceLayout>` from `components/common/`. It owns the section container,
  the collapsible left panel (title · action · toolbar · body), the center area
  and the right panel — a drawer that is **collapsed by default** and shows as
  an icon rail (`<RightPanel>` renders it expanded, with tabs and badges).
  Sections supply slot content only; they must not rebuild their own split.
- Every section goes through it, including Settings and Admin (`SettingsShell`
  renders a `WorkspaceLayout` with no right panel). Do not reintroduce
  per-section container/split classes — `chat-app-container`,
  `files-panel-main`, `knowledge-base-container`, `settings-container` are gone.
  Panel metrics are CSS variables: `--ws-right-width` and the row tokens sit on
  `.workspace`, and a section may tune those from its own modifier (e.g.
  `.workspace--files` narrows `--ws-indent`). `--ws-left-width` is the exception
  — it lives on `:root` because the drag handle rewrites it there for every
  section at once; a `.workspace--*` override would outrank `:root` and freeze
  that section's width, so never redeclare it.
- The header of the center area is the shared `.workspace__head`
  (`common/workspaceLayout.css`) — one rule with the left/right
  `.workspace__side-head`, so all three columns start on one line; only the
  padding differs per class, and it sets `box-sizing: border-box` itself (there
  is no global one). Keep them in that one rule: two copies drift and the step
  at the column seam comes back. It is **one row**: put the object's name in
  `.workspace__head-title` (ellipsis, `.workspace__head-edit` for its
  inline-rename form) and the buttons in `.workspace__head-actions`; nothing may
  wrap. Metadata does not go there — dates, versions, path, author belong to the
  right panel's Info tab (that is why the chat's created-at, the KB date row and
  the KB "up one level" button, a duplicate of the last breadcrumb, are gone).
  The one section still outside this is Settings/Admin: `SettingsShell`'s head
  is deliberately two rows (title + subtitle) and does not line up — migrate it
  when that subtitle finds another home, don't copy its metrics anywhere.
- A path in a header is the shared `<HeadCrumbs>` (`common/HeadCrumbs.jsx`):
  sections pass `items` (`{ key, label, onNavigate? }` — an item without
  `onNavigate` is the current one) and `trailingSep` when the header's own title
  continues the chain (knowledge base) rather than ending it (files). Sections
  pass the **whole** chain — overflow is HeadCrumbs' business: a chain that
  doesn't fit gets its middle collapsed into one clickable `…` (`utils/
  breadcrumbs.js`, click restores it), and what still doesn't fit scrolls
  horizontally with the scroll pinned to the **end** — the nearest folder and
  the open object matter more than the root, which the tree on the left already
  shows. Collapsing is decided by measuring (`scrollWidth` vs a `ResizeObserver`
  on `.workspace__head`, never on the crumbs themselves — their own width
  follows their content and observing it loops), never by counting crumbs: six
  short repo segments fit where three knowledge-base titles don't. Don't write a
  third breadcrumb.
- Panel layout is **controlled state that lives in the URL** (`?left=0`,
  `?right=<tab>`), owned by `useAppNavigation` and threaded down as the `panels`
  prop from `App`. Per-section layout is remembered in `localStorage`
  (`panelState.js`). Never keep panel open/closed state locally in a section.
- The right panel is where "everything *about* the thing" goes; the center is
  the thing itself. Every section opens with an **Info** tab (first, always) —
  chat: dates/AI topic/model; knowledge base: type, dates, versions; files:
  path metadata + the last commit that touched it. It renders through the
  shared `<InfoList>` (`common/InfoList.jsx`): sections pass
  `[{ label, value, mono, block }]` rows and it drops the empty ones — don't
  hand-roll another `dl`. Then come the per-section tabs: chat → attachments;
  knowledge base → summary, folder contents, attachments (`detailSidebar.jsx`
  builds them); files → nothing else yet. Tab keys shared across sections live
  in `constants/rightTabs.js` (`RIGHT_TAB`) so `?right=info` means the same
  thing everywhere; `DOC_TAB` holds the knowledge-base-only ones and is
  right-panel keys, not center tabs.
- State shared by the center and the right panel (the KB content draft,
  fullscreen, history) lives in `useDetailPanel`, hoisted to `KnowledgeBase`.
  It is no longer remounted per document, so it resets on `nodeId` change
  itself — keep that reset when adding state to it.

### URL scheme

`useAppNavigation` is the only owner of navigation state and the only writer of
`window.history`. The **path carries the opened resource**, the **query carries
screen state**:

```
/chat/<chatId>                   /knowledge/doc/<docId>
/knowledge/search?q=&mode=       /files/<path/to/file>
/admin  /settings                (+ ?left=0 / ?right=<tab> anywhere)
```

Only non-default values are written, so addresses stay short. Old query-form
links (`?view=`, `?doc=`, `?path=`, `?chat=`, `?tab=`) still open and are
canonicalized on load — keep that fallback when touching `readUrl`. Panel
toggles use `replaceState` (they are not navigation); real transitions use
`pushState`. The write mode is set by whoever triggers the change (`pushNav` /
`replaceNav`), never reset from the write effect — a `setNav` that bails out
would otherwise leak the mode into the next real transition.
Adding a new top-level path means updating `SpaForwardController` too — its
mappings must cover nested paths.

### Left panel (списки и деревья)

- Every row of a left panel — chat list, knowledge tree, file tree, settings
  groups — is the shared `.ws-item` from `common/sidePanel.css` (with
  `.ws-item__chevron/__icon/__label/__actions/__action`, `.ws-list`, `.ws-hint`).
  A section adds only modifiers for its own behaviour **on top of** the shared
  block — the KB tree's `.tree-row--dragging`/`__drag-handle`/`__system-badge`,
  the file tree's `.ws-item--nowrap` horizontal scroll — never a parallel row
  family of its own (`chat-list-item`, `file-tree-row`, `settings-nav__item`
  were exactly that, and are gone).
- Metrics come from tokens on `.workspace` (`--ws-gutter`, `--ws-row-min-h`,
  `--ws-row-font`, `--ws-indent`): the panel head, the action
  button, the search widget and the rows all sit on one vertical. A section may
  override a token from its own `.workspace--*` modifier, but only with a
  comment saying why (files use a smaller `--ws-indent` — repo paths are deep).
- Row height is `min-height` only, never padding + content: a row with an action
  button would otherwise be taller than one without.
- Search above the list is the shared `<PanelSearch>` from `components/common/`
  (trigger → field → portal dropdown, built on `useSearchDropdown`). A section
  passes only `search` (fetch) and `describeItem` (icon/title/subtitle/badge);
  common labels live in `common.json` under `panelSearch.*`. Don't write another
  search widget — `ChatSearch`/`FileSearch`/`TreeSearch` are 40-line adapters.
- Keyboard: the list **container** is the single tab stop (`tabIndex={0}` +
  `onKeyDown={useListNavigation()}`), rows carry `data-ws-item` + `tabIndex={-1}`
  and are reached with arrows (Enter/Space opens, ←/→ collapses/expands through
  `[data-ws-chevron]`, Home/End jump). Rows cannot be `<button>`s — they already
  contain action buttons — so this is how they become reachable at all; keep the
  attributes when adding a new kind of row. ARIA: trees are
  `role="tree"`/`treeitem` + `aria-level`/`aria-expanded`/`aria-selected`, flat
  lists are `role="listbox"`/`option`, and layout wrappers between them carry
  `role="none"` so the rows stay owned by the tree. The settings list is the one
  exception to the single tab stop — its rows *are* `<button>`s, so the container
  keeps no `tabIndex` and arrows just supplement native Tab/Enter.
- `useListNavigation` scrolls the focused row into view **vertically only**
  (`focus({ preventScroll: true })` + a manual `scrollTop` nudge). Don't swap it
  back to `scrollIntoView`: file-tree rows are wider than the panel, and
  `inline: 'nearest'` on them resets the horizontal scroll to 0.
- The left panel's width is draggable and lives in one shared store
  (`useLeftPanelWidth`), not in component state or the URL: several
  `WorkspaceLayout`s are mounted at once (chat and knowledge base always are), so
  per-instance width would make the panel edge jump between sections again.
  Dragging writes the `--ws-left-width` CSS variable on `:root` directly and only
  commits to the store on pointer-up — a `setState` per `pointermove` would
  re-render the whole section.

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

- Use the shared button classes from `components/common/buttons.css`: `btn`,
  `btn--primary`, `btn--ghost`, `btn--danger`, `btn--sm`, and `icon-btn`
  (+ `icon-btn--danger`, `icon-btn--done`, `icon-btn--star`) for icon-only
  buttons. That file is now the only place button looks live — the panel-local
  families (`set-btn`, `set-icon-btn`, `detail-icon-btn`, `new-chat-button`,
  `kb-new-doc-button`, `chat-header-delete`, …) are gone. Don't add new ones.

### CSS

- One naming scheme: BEM (`block__element--modifier`), lowercase-hyphenated
  block names. No new abbreviated prefixes (`tcd-`, `fcd-`, `set-`).
- File layout: shared styles live next to their component in `common/`; panel
  styles go in `<panel>/styles/<topic>.css`, one topic per file — the pattern is
  `chatPanel/styles/` behind the `chatWindow.css` import barrel, and
  `knowledgeBasePanel/styles/`. Don't grow monolithic per-panel files.
- CSS is plain (no modules/preprocessor); classes are global — prefix with the
  block name to avoid collisions, and never reference another panel's classes
  (shared chrome belongs in `common/`).
- There is **no global `box-sizing: border-box`** in the project. Any rule that
  sizes a box (`min-height`, `height`, `width`) must set `box-sizing` itself, or
  padding and border silently add to it — and `<button>`s behave differently
  from `<div>`s, since the UA stylesheet gives buttons `border-box`.

### Components & hooks

- Components render; hooks own state/API orchestration; pure logic goes in
  plain `.js` modules next to the feature (`treeOps.js`, `fileChips.js`).
- Keep files focused: a file approaching ~300 lines or holding 2+ exported
  components is due for a split — `wc -l` answers this, so no list of offenders
  is kept here (the ones that used to be listed had all drifted). `ChatWindow.jsx`
  is the worst of them and the one to chip away at when you are in it anyway:
  only its layout has been extracted so far.
- Reuse the shared hooks before writing new plumbing: `useSearchDropdown`
  (search-button → dropdown widgets), `useEscape`, `useDocPreview`/
  `useFilePreview` (both built on `usePreviewCache` — the module-cache preview
  pattern; new preview kinds should reuse it too), `useCopyFeedback` (any
  copy-to-clipboard button: `writeText` + the transient "copied" state and its
  timer). The copy hook has six pre-existing copies of its body still inlined
  (`CodeBlock`, `Message`, `SummarySection`, `MarkdownEditor` ×2, the tool-call
  panels) — migrate them on touch, don't add a seventh.
- Async effects must be cancellation-aware (`cancelled` flag or AbortSignal in
  cleanup), matching the existing preview hooks.
- The two trees are intentionally separate: `knowledgeBasePanel/TreeNode`
  (editable: drag-drop, pagination) vs `filesPanel/FileTreeNode` (read-only).
  Do not unify them.
- New user-facing strings go through i18n (`en` + `ru` locale files), never
  hardcoded.

### Visual checks

Before checking a component by hand, read `frontend/tests/visual/cases.yaml` —
scenarios and data for already-checked components live there, with fixtures in
`frontend/tests/visual/fixtures/`. Add new checks as cases in the same format
and never rename an existing `id` (it is the future story/baseline name).
`./run/test.sh smoke` boots the app and screenshots it.

## Before a PR

`./run/test.sh pre-pr` — `spotlessCheck` · `:backend:test` · `build`. All three
run in the web sandbox too, IT tests included.
Dependency locking is on — after changing deps run
`./gradlew resolveAndLockAll --write-locks`.

## Environment quirks (temporary)

Scaffolding for two environments that will outlive their need: runners with
no JDK 25, and the Claude Code web sandbox. `run/test.sh` already applies all
of it — read on only when running Gradle by hand or when the wrapper itself
misbehaves. Delete a subsection once its environment is gone.

### Testing on JDK 21 (no JDK 25 available)

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

`run/test.sh` applies this by itself: it reads the major version of the JDK it
would use and adds both flags below 25. Force the decision with `KB_JAVA21=1`
(always apply) or `KB_JAVA21=0` (never) when the heuristic guesses wrong — e.g.
when Gradle is pinned to a different JDK than the `java` on `PATH`.

### Building & testing in the Claude Code web sandbox

**`./gradlew` does not work here** (the gradle-9.6.1 download is blocked) —
use the system Gradle at `/opt/gradle/bin/gradle`.

A SessionStart hook (`.claude/hooks/session-start.sh`, web-only) already sets
`LANG=C.utf8`, exports `GRADLE=/opt/gradle/bin/gradle`, and pre-compiles
backend main+test classes with the JDK 21 init script — so the dependency
cache is warm and `spotlessCheck`/unit tests start fast without extra setup.

`run/test.sh` needs nothing extra here: it picks up the hook's `GRADLE`, adds the
init script, and starts `dockerd` itself for the `*IT` suites. Maven Central,
plugins.gradle.org, nodejs.org and Docker Hub are all reachable; only the Gradle
distribution download is blocked.

### Testcontainers Docker image caching

`*IT` suites pull `pgvector/pgvector:pg17` via Testcontainers on first run, which can
be slow or fail transiently — if `./run/test.sh` hits a `ContainerFetchException` for
it, run `docker pull pgvector/pgvector:pg17` and retry; the image stays cached locally
after that.

### Visually validating the frontend in the web sandbox (Playwright)

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
defaults to ASCII and `GitService` throws on non-ASCII repo paths).

`./run/test.sh smoke` is the whole thing in one command — it builds the jar and
runs the script; the by-hand form is in the script's Prerequisites/Usage header.
Copy its `chromium.launch()`/env-var setup for ad hoc checks beyond a screenshot.
