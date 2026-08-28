---
paths:
  - "backend/**"
---

# Backend: chat persistence and tool calls

The least guessable part of the backend. Read this before touching chat
persistence (`service/chat/memory`) or the tool-call UI endpoints.

## `@Tool` signatures

No primitive parameters, and every missing argument answered on purpose through
`ToolArgs` (`kb.tools`). `ToolArgumentGapsTest` scans for the tools and enforces
both — read its javadoc for the why before writing a new `@Tool`.

## Tool-call storage

- **There is no tool-call table.** Protocol tool data — the assistant's calls and
  the TOOL responses — lives in `chat_message.tool_data` (JSON; see `ToolData`,
  `ToolDataToJsonConverter`), alongside the message it belongs to. UI-only
  metadata (names, argument gists, the statuses shown in chat) lives in the
  message `meta` as `ToolInvocationMeta`/`ToolInvocation`. Never mix the two:
  `tool_data` is what the LLM protocol needs to replay history, `meta` is what
  the frontend renders.
- **`callId` is the join key.** Every call and response carries the protocol
  `callId`. `tool_call_index` (`ToolCallIndexEntity`) maps
  `conversationId + callId` → the `chat_message` ids holding the full details:
  the issuing ASSISTANT segment, and the TOOL response row once it arrives.
  `ToolCallService.findToolCallDetail` is a plain lookup through it — do not
  reintroduce positional or offset arithmetic over message history.
- **The index is filled at persist time** (`ChatHistoryService.append` calls
  `ToolCallService.index`), not by a background job. Keep it in sync when
  changing how messages are saved — `repairDanglingToolCalls` writes its
  synthetic TOOL row outside `append` and indexes it itself; a tool response
  that misses the index leaves its call looking unfinished forever.

Migrations for this live in both `db/migration` (Postgres) and `db/migration-h2`.

## Message `meta`

- **A new `ChatMessageMeta` field needs a second edit**, in the `MetaJson`
  projection inside `ChatMessageMetaToJsonConverter` — both the read and the
  write side. The projection lists its fields explicitly (so a column written by
  another version stays readable), and nothing in the compiler notices a field
  missing from it: the value simply persists as `null`. Mocked-repository tests
  don't notice either — `ChatMessageMetaRoundTripTest` is what fails, and it
  stops compiling when a field is added, which is the point.

## Chat memory

- **`ChatMemory` is ours** — `ChatHistoryMemory` over `ChatHistoryService`. Do
  not swap in `MessageWindowChatMemory`: its window would trim on the write path
  by a message count unrelated to what the model actually receives (that is
  `SummarizeService`), and its `add` re-reads the whole conversation on every
  advisor call. Writes are append-only; `append` gets the new row's position from
  a single max query, so callers never hand it the history.
- **Everything a run learns only at its end is written in one pass:**
  `ChatHistoryService.markRunResult` stamps the tool-call plaques, the model and
  the run's tokens together. Do not split it back into two writes: the plaques
  find un-enriched segments by `meta == null`, so a model stamped first hides the
  run's own tool calls and the plaques never appear. It marks the rows after the
  last USER message through the one shared rule,
  `ChatHistoryService.tailAfterLastUser`; do not re-derive that cut.
- **Live `TOOL_CALL` events number calls per run** (`ToolCallEventPublisher`
  over the counter in `RunScope`), matching `ToolInvocationCollector`'s counter.
  Do not recompute `callIndex` by scanning the tail of the history: after a retry
  the tail also holds the failed run's segments, which that counter never saw.

## H2 sample data

`backend/src/test/resources/db/sample-data.sql` is a ready-made H2 dataset — a
real captured chat conversation plus documents, attachments and tool calls — for
manual QA and as a `@Sql`-loadable fixture in tests. It targets the
`db/migration-h2` schema only: do **not** run it against real Postgres, the array
and vector column types differ. Full contents and rationale are in the file's own
header comment.

`SampleDataFixtureTest` is both the worked usage example (`@Sql` on an H2
`@DataJdbcTest`, the same pattern as `DocumentServiceUnitTest`) and the
regression test that keeps the fixture in sync with `db/migration-h2` — run it
after touching either.
