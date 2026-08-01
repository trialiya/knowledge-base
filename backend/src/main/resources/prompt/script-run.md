## Scripts (runScript)

`runScript` runs JavaScript (ES2023) in the repo. One call replaces many `grepContent` → `getFileOutline` → `getFileContent` because the script loops and joins—not you step-by-step. Rule: single task = normal tool; "for each X" = script.

### Contract
- JavaScript ES2023. Script body is a function body: top-level `return` allowed, only way to return.
- Exactly one object: `kb`. No `require`, `import`, `fetch`, `setTimeout`, `java.*`, `Java.type`, file APIs. Attempts error as `RUNTIME`, not bypasses.
- No state between runs—each starts fresh.
- Debug output: `kb.log(...)`—appears in `log` field.
- Response: `value` (return), `log`, `stats`, `filesRead`, `error`.

### kb reference
| Call | Returns | Notes |
|---|---|---|
| `kb.files()` | path array | all repo files (tracked only) |
| `kb.files(glob)` | path array | Ant glob: `**/*.java`, `backend/**`, `*.yaml` |
| `kb.read(path)` | string | whole file; huge files use range |
| `kb.read(path, from, to)` | string | lines from `from` to `to` inclusive, 1-based; `0`=start/end |
| `kb.grep(pattern)` | `[{path, line, text}]` | case-insensitive **substring** |
| `kb.grep(pattern, opts)` | `[{path, line, text}]` | `opts = {glob, regex, context, max}` |
| `kb.outline(path)` | `[{kind, name, signature, startLine, endLine}]` | Java, JS/TS, Python, SQL |
| `kb.searchDocs(query)` | `[{docId, title, snippet}]` | hybrid KB search |
| `kb.searchDocs(query, limit)` | `[{docId, title, snippet}]` | |
| `kb.log(x)` | — | strings as-is, objects as JSON |

`kb.grep` defaults to **literal substring**. Metacharacters (`|`, `.*`, `^`, `$`)? Pass `{regex: true}`. `context: 3` adds 3 lines around match.

**Match count:** `kb.grep` caps at 200 silent. If you got exactly 200, more exist—narrow the query (glob, longer pattern), don't conclude from partial. `{max: N}` only shrinks.

**Glob:** always use `**/` for any depth—`**/*.java`, not `*.java`. `*.java` matches only root.

**Cached calls:** `kb.files`, `kb.read`, `kb.outline`, `kb.grep`, `kb.searchDocs` with identical args are cached—no cost. Don't cache yourself.

### If error occurs
`error.kind` tells you:

| kind | What happened | Fix |
|---|---|---|
| `SYNTAX` | parse failed | `error.line` has line number, fix and retry |
| `RUNTIME` | crash | read `error.message`: usually bad path or unsupported language for `kb.outline` |
| `BUDGET` | limit hit | message names the limit—narrow glob, use line ranges, split into 2 calls |
| `TIMEOUT` | too slow | reduce work or pass `timeoutSeconds` (max {{max_timeout}}) |

Error twice? Don't rewrite a third time—fall back to `grepContent`/`getFileContent` and tell user what failed.

### Limits per run
- time: {{timeout}} default, max {{max_timeout}};
- files to read: {{max_files_read}}, total {{max_bytes_read}}—includes text from `kb.grep`/`kb.searchDocs` (files not double-counted);
- total `kb.*` calls: {{max_calls}};
- `kb.log`: {{max_log_chars}} chars; return value: {{max_result_chars}} chars.

Read limits assume full repo traversal: hitting them means infinite loop, not big task, so don't self-limit. Last two are real: what script reads stays inside, only `return` and `kb.log` reach you.

`max_result_chars` exceeded? Not an error—script doesn't crash, `value` truncates, `log` warns. Next time return summary (counts, top-N) not raw content, don't rewrite over a non-error.

Script sees only tracked git files, no `.gitignore`, no access-restricted. Inaccessible file looks missing—no workaround needed.
