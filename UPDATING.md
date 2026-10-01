# Updating

What an upgrade of Knowledge Base asks of you: breaking changes, deprecations,
migration steps, and anything that can cost downtime. One section per version,
newest first.

Entries are written in the pull request that makes the change, not recalled
from memory at release time — that is the whole point of the file. At release
they are the raw material for the release notes.

This is not the changelog. [`CHANGELOG.md`](CHANGELOG.md) says what is new in a
release and is read by everyone; this file is read by whoever performs the
upgrade, and carries only what demands an action or a decision from them. A
feature that just works after the upgrade belongs there, not here.

**How to write an entry.** A heading naming the change, then two things: what
stops working as before, and what to do about it. Name the config keys, files
and commands involved — the reader is holding a deployment, not a diff.

## Unreleased

### `kb.search.keyword.limit` is now applied

The key was bound but never read: keyword search over documents always returned
the 20 most recently updated matches. It now sets that number for keyword search
whenever the caller passes no `limit` (`GET /api/documents/search`,
`/search/grouped`, the `searchDocuments` tool in `keyword` mode), and for the
keyword half of hybrid search; a `limit` passed in keyword mode is now honoured
too. A deployment that left the key at the default sees no change; one that set
it to another value gets that value from now on — check it before upgrading.

### Git and search REST responses leave out empty fields

`GET /api/git/tree`, `/browse`, `/files/search`, `/files/content` and
`/files/outline` no longer print a field that is empty or at its usual
value: `GitFileNode.size` of a directory, `GitFileContent.commit` / `content` /
`language` / `fromLine` / `toLine` when `null`, `binary` when `false`, and
`tracked` when `true` (only `"tracked": false` is printed). In `GET /api/documents/search`,
`parentList` is left out for a top-level document instead of being `[]`. A line range asked
of `/api/git/files/content` now stops at 512 KB, like a whole read; `toLine`
says where.

The bundled UI already reads them that way. A script or integration of your
own that reads these endpoints should treat a missing key as `null` / `false`
/ `[]` — and a missing `tracked` as `true`.

### Scheduled script results are logged at DEBUG

A scheduled script that finishes cleanly now writes one INFO line with its
name and run time; the value it returned moved to DEBUG. A deployment that read
script output from the application log at the default level no longer sees it.
To get it back, set
`logging.level.io.github.trialiya.kb.service.chat.script.ScheduledScriptService=DEBUG`.
Failures are still logged at WARN with the error.

## 1.1.0

Nothing beyond the candidates. From `1.1.0-RC3` the upgrade asks nothing.
From `1.0.0`, apply the three sections below, oldest first: `1.1.0-RC1`,
`1.1.0-RC2`, `1.1.0-RC3`.

## 1.1.0-RC3

### `replay-reasoning` is on by default

`kb.chat.default-model.replay-reasoning` and `kb.chat.models[].replay-reasoning`
now default to `true`: a stored model reasoning goes back to the model as
`reasoning_content` on its answers in the history. This is what keeps the
prompt cache across turns with tool calls. An endpoint that does not
know the field rejects every request carrying it (Groq, for one) — set
`replay-reasoning: false` on that model. Turn it off only there: switching it off
changes what every chat with stored reasoning sends, so their cached prompt
prefixes stop matching at once and each turn after one with tool calls is paid
in full again.

### Document, search and attachment API omit empty optional fields

The same rule now covers documents and attachments: `DocumentNode` leaves out
an empty `summary` and `summarySourceVersion`, `SearchResult` an empty
`summary` and `parentList`, `DocumentGrepMatch` an empty `sectionPath`, and
`Attachment` an empty `documentId`, `conversationId`, `summary` and
`sourceUrl`. This changes the REST responses under `/api/documents/` and
`/api/attachments/` as well as what the chat model reads. As with the git
entry below: treat a missing key as `null`.

### Git API omits empty commit and diff fields

`GitCommit` and `GitDiffEntry` no longer print their empty optional fields:
`body`, `files`, `oldPath`, `patchHeader` and `patch` are left out of the JSON
instead of coming as `null`. This applies to the REST responses under
`/api/git/` (`/commits`, `/commit`, `/outgoing`, `/commits/search` and
`/status`) as well as to what the chat model reads. A client that tells
"field is `null`" from "field is missing" must treat a missing key as `null`;
one that reads the fields with a null-safe accessor needs nothing.

## 1.1.0-RC2

### Section paths under a repeated heading change

A subsection of a repeated heading is now addressed through its parent's
suffix: under the second `# FAQ`, `## Вопрос` is `FAQ[2] > Вопрос`, not
`FAQ > Вопрос[2]`. Only documents where a heading path repeats are affected.
A path saved before the upgrade — in an old chat the model continues, or in a
`/knowledge/doc/N?find=…&section=…` link — no longer finds that section: the
model gets a "not found" error that lists the current paths, the link
falls back to the first match in the document. Nothing to do.

### A script schedule naming an unknown project fails the start

`kb.script.schedules[].project` is now checked against `kb.projects` at
startup, like the rest of the entry. A schedule whose `project` names no
configured project used to register and fail on every firing; now the
application refuses to start with `kb.script.schedules[<name>].project:
unknown project "<id>"`. Fix the id or remove the entry before upgrading.

The same id is no longer replaced with the default project when a saved script
is run: `POST /api/settings/script/run-saved` with an unknown `project` answers
400 instead of reading the script from the default repository, and the model's
`runSavedScript` in a chat whose stored project no longer exists gets an
"Unknown project" error. Nothing to do unless a client relied on the fallback.

### MCP tools keep their names, duplicates are left out

MCP tools are no longer renamed `alt_N_<name>` when a server changes a tool's
description or reports a new version, and a tool name is offered to the model
once: when two connections advertise the same name, the connection listed
first under `spring.ai.mcp.client.*.connections` keeps it, and an MCP tool
named like a built-in one is not offered at all. Each case is logged once as a
WARN. If the model was relying on an `alt_N_` name, or on the second of two
same-named tools, rename the tool on the server or reorder the connections.
A custom `McpToolNamePrefixGenerator` bean is still used as is.

## 1.1.0-RC1

### The chat title is no longer a tool call

The `recordChatInsights` tool is gone. The chat title is now written by a
separate background request after an answer (on the 1st, 3rd, 10th answer and
every tenth after that), which reads a short tail of the conversation.

- **A system prompt of your own** (`kb.system-prompt.prompt` pointing at a copy
  of `sys.md`): delete the lines that tell the model to call
  `recordChatInsights`. Left in, they make the model call a tool that no longer
  exists at the start of every answer — a wasted round each time.
- **Cost.** The request is paid, and by default it runs on the chat's default
  model. Point it at a cheaper one with `kb.chat.topic.model`
  (`KB_CHAT_TOPIC_MODEL`), turn reasoning down with
  `KB_CHAT_TOPIC_REASONING_EFFORT` / `KB_CHAT_TOPIC_THINKING`, or switch naming
  off with `KB_CHAT_TOPIC_ENABLED=false`.
- **Old chats keep calling the tool for a while.** The `recordChatInsights` calls and their
  responses stay in the stored history of chats created before this change, and the model tends to
  copy that pattern: the call fails with "unknown tool" and costs one extra round. The chat is not
  broken by it — the error is answered and the run continues, and the failed call is not shown in
  the feed — and the habit fades as the history is compacted. Stripping those calls from the
  history when the prompt is built is tracked in
  [#425](https://github.com/trialiya/knowledge-base/issues/425); until then, `/compact` on a busy
  old chat is what ends it for good.
- **Existing chats** are named once more on their next answer: the migration
  adds `chat_topic.ai_topic_turn`, and a chat without it counts as never named.
  A title the user gave is never touched.

### Scripts can now be run from a repository manifest and from attachments

A deployment that already has `kb.script.enabled=true` gains one capability on
upgrade without doing anything: the model can run a JavaScript **attachment** —
its own chat's or a knowledge-base document's — with the new `runSavedScript`
tool (`attachment:<id>`). Such a run is read-only (unless you turn on
`kb.script.attachment-edit`, off by default) and uses the same
sandbox and budgets as `runScript`, so it reads nothing the model could not
already read; what is new is that the code comes from whoever uploaded the file
rather than from the model.

- To keep it off: `kb.script.attachment-run: false` (`KB_SCRIPT_ATTACHMENT_RUN`).
  It defaults to `true` because it only narrows what `kb.script.enabled` already
  granted.

Scripts the **repository** declares are opt-in and change nothing until
configured: set `kb.projects[].scripts-manifest` (`.kb/scripts.yaml` by
convention) to the file in which that repository lists its scripts. Without the
key the project has no saved scripts, and with no project having it — and
attachments off — the tool is not offered to the model at all. The files a
manifest names must be tracked by git; the format is in
[`docs/проект/конфигурация.md`](docs/проект/конфигурация.md).

### MCP connections no longer open during startup

An MCP server that was unreachable used to take the whole application with it:
the client was initialized while the Spring context was coming up, and a refused
connection — a wrong URL, a server that was down, an `npx` that is not installed
— failed the bean and the boot. Connections are now opened in the background
after startup, probed per connection, and retried while they are down; an
unreachable server costs its own tools and nothing else.

One setting changed in `application.yaml`, and a deployment that overrides it
should know why it is there:

- `spring.ai.mcp.client.initialized` is now `false`. Setting it back to `true`
  restores the old startup behaviour — including the failed boot.

New key: `kb.mcp.retry-interval-ms` (`KB_MCP_RETRY_INTERVAL_MS`, default 60000)
— how often the connections are probed again: a server that came up is picked up
within that interval, and one that stopped answering is marked down within it.
It must be positive.

A connection that goes down keeps its tools in the model's tool list: they answer
with a «server unavailable» error instead of disappearing, because the tool list
is part of the prompt prefix providers cache by — withdrawing a tool would
invalidate every conversation's cached prefix, twice per outage. Only a
successful probe rewrites the list, so a tool the server itself stops
advertising is still dropped. `GET /api/settings/tools` gained an `available`
flag saying which is which.

No action is required for a deployment that runs with MCP off, or with servers
that are up. For one that was relying on a failed startup to signal a broken MCP
configuration: that signal is now the «Настройки → Инструменты» panel, which
reports each connection as `PENDING`/`UP`/`DOWN`, and the log: a `WARN` when a
connection goes down, an `INFO` when it is up again (a server that stays down is retried quietly, at DEBUG,
so it cannot bury the log).

### `getUncommittedChanges` no longer reports untracked files unless asked

The tool answers about the tracked half of the working tree — what the next
commit will carry. The untracked files a project admits through
`kb.projects[].allow-globs`, listed under status `U`, now come only with the
new `includeUntracked: true` argument (default `false`).

This matters only to deployments that configure `allow-globs`. If you rely on
the assistant noticing its own writes into that area — build notes, local
scratch files — say so in the project's instructions, or expect it to ask for
them itself: the system prompt tells it about the argument on every project
that has an admitted area. Nothing to migrate, and the files panel
(`GET /api/git/status`) is unchanged: it still shows both halves always.

## 1.0.0

The first release. All four entries below matter to deployments that were
already running from `main`, not to a fresh install.

### The built JAR is now `backend/build/libs/kb.jar`

`bootJar` no longer puts the version in the artefact name — it was
`backend-<version>.jar`, so every release silently broke whatever referenced
it by path. The version stays available in the manifest
(`Implementation-Version`) and in the Admin panel.

Everything inside the repository — `run/run.sh`, `run/run.bat`, `run/run.ps1`,
`scripts/playwright-smoke.js`, `docker/Dockerfile` and the documentation — is
already updated. **Update your own deploy scripts** if they name the JAR.

### Flyway validates applied migrations

`spring.flyway.validate-on-migrate` is back to Flyway's own default, `true`. A
migration edited after it was applied now fails the start instead of letting
the schema drift away from `db/migration` unnoticed. From 1.0.0 on, schema
compatibility is a promise, and that is what keeps it one.

No migration in this repository has ever been edited after being applied, so a
deployment that only ever ran released code is unaffected. If your database
does carry a checksum mismatch, the start will name the migration: repair it
with `flyway repair`, do not turn the setting back off.

### Forced UTF-8 encoding is applied again

The encoding keys in `application.yaml` moved to `spring.servlet.encoding.*`,
the only prefix Spring Boot 4 binds (`force: true` had quietly stopped working)
— rename yours if you override them, e.g. `SERVER_SERVLET_ENCODING_FORCE` →
`SPRING_SERVLET_ENCODING_FORCE`.

### The script engine is pinned to GraalJS 25.0

`org.graalvm.polyglot:polyglot` and `js-community` go from `25.3.4.1` back to
`25.0.4.1`. The polyglot artefacts have to match the GraalVM that builds the
native image (25.0.4), and the version is shared with the ordinary JVM build —
there is one version for both. Scripts run on a slightly older JS engine as a
result; nothing in the scripting API changes. See
[Нативный образ GraalVM](docs/проект/нативный-образ-graalvm.md) for the version
coupling.
