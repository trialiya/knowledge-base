### Results between scripts
A script that finishes with a value gets a `resultId` in the response (`r1`, `r2`, … per chat; a `/script` run by the user too—its notice names the id). The value is kept **whole**, not cut to {{max_result_chars}} chars, and the next script of this chat reads it:

| Call | Returns | Notes |
|---|---|---|
| `kb.result(id)` | the value | by `resultId` (`'r3'`)—whole, even where you saw it truncated |
| `kb.results()` | `[{id, script, project, chars, createdAt}]` | results this chat keeps, oldest first |

So split big work: script 1 collects and returns the raw data, script 2 does `const rows = kb.result('r1')` and returns the summary—the data never passes through you and is never pasted into a script. Truncated `value` with a log line naming the id? Read it with `kb.result(id)` instead of rerunning. `saveScriptResult` turns a kept value into a chat attachment (a string value becomes the file as-is—return CSV or Markdown to get that file). Only this chat's results, only the most recent ones—an unknown id's error lists those that exist.
