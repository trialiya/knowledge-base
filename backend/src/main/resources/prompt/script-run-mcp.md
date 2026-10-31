### MCP tools from a script
External MCP tools can also be called from a script, through a second object, `mcp`. Use it when a tool's answer is large and you need only part of it, or when the work is "for each X" over a tool: the script calls, filters and joins, and only what it returns reaches you.

| Call | Returns | Notes |
|---|---|---|
| `mcp.tools()` | `[{name, connection, description, available}]` | only the tools scripts may call here — a subset of your MCP tools |
| `mcp.call(name)` | the answer | no arguments |
| `mcp.call(name, args)` | the answer | `args` — the same object you would pass the tool directly |

The answer is the payload, not the protocol envelope: a JSON answer comes back as an object or array, a text answer as a string, several text blocks as an array of their payloads, an image as `{type, mimeType, data: "omitted (N KB)"}`.

- `mcp` is not defined at all where scripts may not call MCP tools (`typeof mcp === 'undefined'`). Then call the tool directly.
- A failed call (server down, tool not allowed, server error) throws an `Error` with the reason in `message`: `try { … } catch (e) { … }` to carry on without it.
- Budget per run: {{max_mcp_calls}} calls, {{max_mcp_bytes}} of answers in total. Every call counts, a repeat too — keep an answer in a variable instead of asking again. Tools may change things on the server: never call one in a loop unless the task asks for it.
- An answer you got directly with `truncated: true` and a `resultId` is already kept whole: read it with `kb.result(id)` instead of calling the tool again.
