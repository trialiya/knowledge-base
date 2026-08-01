---
paths:
  - "backend/**"
---

# Backend: chat persistence and tool calls

The least guessable part of the backend. Read this before touching
`ChatMemoryService`, chat persistence, or the tool-call UI endpoints.

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
  `ChatMemoryService.findToolCallDetail` is a plain lookup through it — do not
  reintroduce positional or offset arithmetic over message history.
- **The index is filled at persist time** (`ChatMemoryService.saveAll`), not by a
  background job. Keep it in sync when changing how messages are saved.
- **Legacy backfill for old data:** `ChatMemoryService.backfillToolCallIds`
  fills in `tool_call_index` for chats recorded before it existed. Do not extend
  it; plan to delete both it and `ToolCallIdBackfillRunner` once all
  environments have backfilled.

Migrations for this live in both `db/migration` (Postgres) and `db/migration-h2`.

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
