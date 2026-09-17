# Changelog

All notable changes to this project are documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and
the project follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).
Product documentation is in Russian and lives in [`docs/`](docs/); this file and
the [README](README.md) are the English entry points. What an upgrade *asks of
you* — breaking changes, deprecations, migration steps — is in
[`UPDATING.md`](UPDATING.md) instead.

## [Unreleased]

### Added

- A dark theme, and a theme picker in the header menu: system, light or dark,
  remembered between sessions. The system preference is followed as long as
  "system" is chosen, so sunset repaints the screen on its own. The dark theme
  is not the light one inverted — it has its own neutral scale and a blue
  accent, because a lightened violet turns the whole screen lilac.
- Files now shows pictures as pictures: an image opened in the file browser is
  rendered instead of the "binary file — preview unavailable" placeholder, and
  an SVG opens as the drawing with a toggle in its metadata row for switching
  between the drawing and its source.
- Files a project admits for reading but git does not track (`allow-globs`) are
  labelled as such where the assistant reports them: in search result cards, in
  the file heading of a grep answer, and in the tool-call details.

### Changed

- The `getUncommittedChanges` tool answers about the tracked half of the working
  tree only. Untracked files from the `allow-globs` area take the new
  `includeUntracked: true` argument — see [`UPDATING.md`](UPDATING.md). The
  Files panel is unchanged and still shows both halves.

### Fixed

- Repository search no longer passes off the previous answer as the new one.
  Switching a filter — the repository above all — keeps the old results on
  screen while the query runs, and they now say so: the category counter gives
  way to a spinner, the stale list is dimmed and taken out of the tab order,
  and the results header names the search in progress.
- The path in a `git grep` answer is read to its end, not to the first dash
  followed by digits. A file named `2024-01-15-notes.md` or `step-01-init.sh`
  used to come back as `docs/2024` at some invented line number, which the model
  would then try to read; with context lines around the match the broken path
  could also drop the whole block from the answer.
- Deleting a folder in the knowledge base says that the whole subtree goes with
  it, and a document open from inside that folder is no longer left in the
  centre pane, where the next edit would have failed with a 404.
- "Load N more" in the document tree survives drag & drop: the next page is
  counted from what the level actually holds, so a node dragged in or out no
  longer makes the button skip a row or run off the end of the list.
- Dropping a node into a folder no longer reads that folder before the move is
  confirmed — the request went out even when the move was cancelled, and coming
  back late it wiped the node that had just been moved.
- A folder expanded by a click on its row says "Loading…" until its children
  arrive, and offers to retry if they do not, instead of looking empty.
- A tree scrolls to the selected row by the start of the name, not its tail: a
  long file name is recognised from its beginning, and the scrollbar gutter is
  no longer counted as visible width. The knowledge-base tree now scrolls to the
  selected document at all — following a link to a document opened the ancestors
  but left the panel at the top of the list.
- The chevron in the "New item" place picker counts folders, the only thing that
  dialog shows, instead of all children: a folder holding only documents no
  longer opens into nothing.
- The breadcrumbs in the header give up their width before the name of the open
  node does, as the layout always claimed they would.
- Hovering a tool-call badge shows on it in the dark theme: the highlight is
  laid over the badge's own colour — which carries the outcome of the call —
  rather than applied as a brightness shift that nothing dark can show.
- The hint above the composer and the send button agree about an empty chat:
  `/compact` is no longer offered on a chat that has nothing to compact.

### Performance

- Opening a chat costs one tool-call index query per page of history instead of
  one per message.

## [1.0.0] — 2026-09-16

The first stable release. Everything from `1.0.0-RC1` through `1.0.0-RC3` is
in it, plus one fix found after the last candidate. Nothing here asks
anything of an upgrade: no breaking change, no migration step, no new
configuration to set.

### Fixed

- Search opened from Files now searches the repository and revision open
  there, instead of defaulting to whatever a previous search left behind —
  the working tree, most of the time. Standing on a branch snapshot, search
  now looks at what is on screen.

## [1.0.0-RC3] — 2026-09-15

What the second candidate turned up in the chat transcript: an interrupted run
that left a hole in the history, and two ways the tool-call feed hid what one
reads it for. Nothing here asks anything of an upgrade: no breaking change, no
migration step, no new configuration to set.

### Fixed

- A run cut off in the middle of a batch of tool calls no longer loses the
  abandoned call on reload: the badge is rebuilt from the stored call list and
  stands where the call was made, instead of living only in the open tab.
- The call that did finish before the run was cut no longer carries the
  interrupted mark. Its result is kept — both the details dialog and the model's
  next request see what the tool returned, rather than "no result" on a tool
  that had one.
- The description of an item in the slash menu is cut from the end, not from the
  front: it shared the class of a file path, which is deliberately reversed so
  that the file name survives, and that reversal ate the words naming the
  command. What is cut off now reads on hover.

### Changed

- The tool-call feed is read where it is written. The badges no longer have a
  scroll window of their own inside the chat transcript, repeated calls to one
  tool show their arguments expanded instead of collapsing into an "×N"
  heading, and consecutive rows of calls run as one feed with no gap between
  them.

### Build

- Vitest 4.1.11 → 5.0.0 and React 19.2.8 → 19.3.0 in the frontend, alongside
  Vite 8.3.0, happy-dom 20.14.3 and `@types/react` 19.3.0.
- JGit 7.7.1 → 7.8.0 and the Spotless plugin 8.10.1 → 8.10.2.

## [1.0.0-RC2] — 2026-09-13

What the first candidate turned up, plus the chat-command work that landed
alongside it. Nothing here asks anything of an upgrade: no breaking change, no
migration step, no new configuration to set.

### Added

- The composer says what a slash command will do before it is sent: a line above
  the field naming the command — or the reason it cannot run right now — the
  trigger highlighted in place, and the command marked in the sent message. A
  question that merely starts with a slash no longer looks like a command.
- Typing `/` in an empty composer opens a list of what belongs there: chat
  commands with their synonyms and arguments, and the chip triggers. `/compact`
  is now discoverable instead of folklore.
- A running server says what it was built from. Version, branch, commit and
  build time travel in the JAR and are served by the system-information
  endpoint, so the Admin panel shows the build rather than leaving it to be
  guessed.

### Fixed

- A call to a tool the run does not have no longer kills the answer. The unknown
  name comes back to the model as an error it can recover from, the way every
  other tool mistake already did, instead of an exception thrown out of the
  stream — which cost a half-written reply, a dangling `tool_calls` tail in the
  history, and the whole prompt that produced it.
- The badge of a tool call left behind by an interrupted run opens its details:
  the arguments and the result were in the database all along, and an
  interrupted call is exactly where one wants to look.
- That badge also stops spinning "running" as soon as the run ends, instead of
  waiting for a page reload to admit the run is over.
- Code blocks in messages are drawn the same way in every variant — one frame, a
  header with the language and a copy button on all of them — and selecting
  inline code no longer drags an extra space into the clipboard.

### Build

- `run/run-spring-aot.sh` rebuilds when the sources change rather than when the
  profile does, so after a `git pull` it no longer starts last week's JAR; a
  repeat run of an unchanged tree now keeps its AOT cache instead of retraining
  it, and the build time goes into the JAR only behind `-Pkb.build.time` (or
  `KB_BUILD_TIME=1`), since a timestamp makes every build a new one.
- `:frontend:yarnBuild` declares its outputs and is skipped when the frontend
  has not changed.
- js-yaml 4.3.1 → 4.3.2 in the frontend dependency tree.

## [1.0.0-RC1] — 2026-09-12

The first release candidate, and the first version shipped at all — so the
sections below describe the feature set as a whole rather than changes against
an earlier release. The feature set is what 1.0.0 will carry; the candidate is
here to be installed somewhere other than the machine it was built on before
that tag is cut.

### Chat over a Git repository

- Natural-language questions about files, commits, diffs and architecture. The
  model does not get a shell: it reads the repository through a fixed set of
  built-in tools and stays inside the configured directory, seeing only files
  git tracks — `.env`, `node_modules` and everything in `.gitignore` do not
  exist for it.
- Answers run in the background on virtual threads and stream over one SSE
  channel per chat, so an answer survives a page reload and continues in a
  second tab.
- Preset modes — Analyst, Developer, Tester — and per-chat model selection when
  more than one model is configured.
- Context compaction: background summarization of the older half of the history,
  plus a full `/compact` (and its automatic twin near the model's context
  limit), so a long chat stays usable instead of failing on request length.
- Attachments in chats, a reusable phrase library, and message search across the
  whole history.
- `runScript` — the model writes a short JavaScript program that walks the
  repository in one call instead of a dozen round-trips. The engine has no
  filesystem at all: scripts reach files only through an injected `kb` object
  under the same tracked-files rules and explicit budgets. Off by default
  (`kb.script.enabled`), with a bench in Settings for trying scripts by hand.
- MCP: tools from external Model Context Protocol servers can be merged into the
  chat model's toolset. Off by default (`kb.mcp.enabled`).

### Knowledge base

- A tree of folders and documents with drag & drop, a Markdown editor with live
  preview, and `@mention` links between documents.
- Version history with diffs and restoring an earlier state.
- AI summarization of documents, with staleness tracking.
- Attachments on documents.
- Export and import against the file system in Markdown + YAML, including
  downloading a subtree as an archive and importing it back through a
  comparison screen.

### Search

- Three modes — keyword, semantic (pgvector embeddings) and hybrid — across
  three categories: repository files, knowledge-base documents and chats.
- Results link straight to the match: the document section, the file line, or
  the exact chat message.
- Embedding indexing runs as a queue with retries, a stuck-task reaper and a
  Postgres-backed cache keyed on the embedding model, so a model change
  invalidates stale vectors instead of mixing them.

### Repository browsing and git commands

- A "Files" panel with the tree, file contents and the latest commit per file,
  in a GitHub-like style; files can be inserted into the chat as chips.
- Git commands for the *user* (not the model): branch with ahead/behind
  counters, fetch, switch and branch creation, stash, commit, reverting a file,
  `merge --abort` and pull. Off by default
  (`kb.projects[].git-commands.enabled`); push is a separate grant on top
  (`push-enabled`).
- Uncommitted changes are visible in the UI and can be committed from the chat
  with a file selection.

### Code editing — opt-in

- `createFile` / `editFile` write to the working tree, only inside the
  repository and only to files git tracks. Off by default
  (`kb.projects[].edit-enabled`), and withheld anyway when the working tree is
  not writable.
- Editing untracked files that a project opens for reading through
  `allow-globs` takes a second flag, `untracked-edit-enabled`.
- No command execution: the model never gets a shell, so builds and tests are
  run by a human.

### Multiple repositories

- Several projects can be served at once (`kb.projects`), with the project
  chosen per chat, carried into tool arguments and file links, and a marker in
  the history when a chat switches project.

### Deployment and administration

- Any OpenAI-compatible API, including locally hosted models, so code need not
  leave the machine. Chat and embedding endpoints can be configured separately.
- PostgreSQL 17 + pgvector for the full stack, or the bundled H2 for a run
  without a database and without semantic search.
- Docker Compose files for both, and plain JAR scripts for Linux, macOS and
  Windows. After the first start the JAR trains an AOT cache and later starts
  are roughly 40% faster (`KB_AOT=0` disables it).
- Admin panel: server and schema information, AI/search configuration
  snapshots, the phrase library, and reindexing.
- An experimental GraalVM native image, off by default and built only when
  asked for (`-Pkb.native`, or `KB_NATIVE=1`): it starts in a fraction of a
  second and takes far less memory, at the price of a long build and the
  scripting engine running interpreted. The ordinary JVM build is unaffected —
  see [Нативный образ GraalVM](docs/проект/нативный-образ-graalvm.md).
- HTTP Basic authentication for a single configured user. `/actuator/health` is
  the only endpoint outside it — plus the H2 console under the `h2` profile,
  which has a database login of its own (see Known limitations).
- Interface in English and Russian, switchable in the header.

### Changed since the pre-release `main`

Assembled from [`UPDATING.md`](UPDATING.md), which is where each of these says
what to do about it. None of them affects a fresh install — they matter only to
a deployment that was already running from `main` before this release.

- The built JAR is `backend/build/libs/kb.jar`: the artefact name no longer
  carries the version, so nothing that references it by path breaks on the next
  release.
- Flyway validates applied migrations again — `spring.flyway.validate-on-migrate`
  is back to its default `true`, so a migration edited after it was applied
  fails the start instead of letting the schema drift.
- Forced UTF-8 encoding works again: the keys moved to the
  `spring.servlet.encoding.*` prefix, the only one Spring Boot 4 binds. Rename
  them if you override them.
- The scripting engine is pinned to GraalJS 25.0, the version that matches the
  GraalVM building the native image. Nothing in the scripting API changes.

### Known limitations

- The default credentials are `admin` / `admin` (`kb.security`). Change them
  before exposing the application beyond localhost.
- The `h2` profile — the one the README's quick start uses — serves the H2
  console at `/h2-console` outside HTTP Basic. The console has a database login
  of its own (`spring.datasource.username` / `password`), but the profile is
  meant for local development and demos, not for a public deployment.
- The model cannot run builds, tests or arbitrary commands.

[Unreleased]: https://github.com/trialiya/knowledge-base/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/trialiya/knowledge-base/releases/tag/v1.0.0
[1.0.0-RC3]: https://github.com/trialiya/knowledge-base/releases/tag/v1.0.0-RC3
[1.0.0-RC2]: https://github.com/trialiya/knowledge-base/releases/tag/v1.0.0-RC2
[1.0.0-RC1]: https://github.com/trialiya/knowledge-base/releases/tag/v1.0.0-RC1
