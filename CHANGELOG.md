# Changelog

All notable changes to this project are documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and
the project follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).
Product documentation is in Russian and lives in [`docs/`](docs/); this file and
the [README](README.md) are the English entry points.

## [Unreleased]

## [1.0.0-RC1] — 2026-09-10

First release candidate. This is the initial public version, so the sections
below describe the feature set as a whole rather than changes against an earlier
release.

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
- HTTP Basic authentication for a single configured user. `/actuator/health` is
  the only endpoint outside it — plus the H2 console under the `h2` profile,
  which has a database login of its own (see Known limitations).
- Interface in English and Russian, switchable in the header.

### Known limitations

- The default credentials are `admin` / `admin` (`kb.security`). Change them
  before exposing the application beyond localhost.
- The `h2` profile — the one the README's quick start uses — serves the H2
  console at `/h2-console` outside HTTP Basic. The console has a database login
  of its own (`spring.datasource.username` / `password`), but the profile is
  meant for local development and demos, not for a public deployment.
- The model cannot run builds, tests or arbitrary commands.

[Unreleased]: https://github.com/trialiya/knowledge-base/compare/v1.0.0-RC1...HEAD
[1.0.0-RC1]: https://github.com/trialiya/knowledge-base/releases/tag/v1.0.0-RC1
