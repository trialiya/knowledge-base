### Scripts: file edits

Two more methods available here. They modify the repo worktree, so stricter rules.

| Call | Does |
|---|---|
| `kb.edit(path, oldString, newString)` | replace **one** exact `oldString` match |
| `kb.edit(path, oldString, newString, true)` | replace **all** matches |
| `kb.create(path, content)` | create new file; fails if exists |

Both return `{path, operation, occurrences}`. Full diffs in response `edits` field—user sees them.

### Rules
1. **Read first, edit second.** `kb.edit` on unread file is error. Either `kb.read` (whole or range) **or** `kb.grep` match in file counts. Grep match is normal for bulk replace: take `oldString` from `hit.text`, no need to re-read formally.
2. **`oldString` exact match** including whitespace and line breaks. Don't compose from memory—take from `kb.read`.
3. **`oldString` unique in file.** Multiple matches? Extend with surrounding lines or pass `true` as 4th arg.
4. **Edits see each other.** Second edit same file operates on changed text—like manual sequential edits.

### All-or-nothing
No `kb.edit`/`kb.create` writes disk immediately. Changes accumulate and apply once when script completes successfully. If script crashes, times out, or user stops—disk unchanged. So:

- Don't fear crash mid-script—no partial edits remain.
- But don't assume "some already applied": either full run or nothing.

Changes **not committed**—user reviews and decides.

### Edit limits
- files to change per run: {{max_edited_files}};
- total changed file size: {{max_edited_bytes}};
- text files only, readable as whole.
