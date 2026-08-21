### Scripts: file edits

More methods available here. They modify the repo worktree, so stricter rules.

| Call | Does |
|---|---|
| `kb.edit(path, oldString, newString)` | replace **one** exact `oldString` match |
| `kb.edit(path, oldString, newString, true)` | replace **all** matches |
| `kb.create(path, content)` | create new file; fails if exists |
| `kb.writeBytes(path, data)` | replace **whole** content of a **binary** file with raw bytes |
| `kb.createBytes(path, data)` | create new file from raw bytes; fails if exists |

Text methods return `{path, operation, occurrences}`, byte methods `{path, operation, bytes}`. Full diffs in response `edits` field—user sees them.

`data` is base64 (as `kb.readBase64` returns) or an array of byte values (as `kb.readBytes` returns). Byte writes are whole-file only: there's no exact-match anchor in binary, so `kb.edit` doesn't apply—read the bytes, change what you need in the array, write it all back. A binary file changes with no line diff: the user sees git's own `Binary files ... differ` line and the two sizes.

**`kb.writeBytes` targets binary files only** (`kb.stat(path).binary`), plus empty files and files this script created itself. On a text file it's refused: a whole-file replacement leaves the user a change with nothing to review. Rewriting text is `kb.edit`, however many occurrences it takes.

### Rules
1. **`kb.edit` needs no prior read**—the exact match is the check: quote `oldString` from a `kb.read`, a `kb.grep` hit or anything else that showed you the current text, and a fragment that is not there fails without touching the file. `kb.writeBytes` is the exception: it replaces the whole file, so the file must have been read first—`kb.read` (whole or range) or `kb.readBytes`/`kb.readBase64` in this script or an earlier one, or `getFileContent`/`getFileOutline`/`editFile` on the same path earlier this turn. `kb.stat` and `kb.hash` don't count—neither shows you the content.
2. **`oldString` exact match** including whitespace and line breaks. Don't compose from memory—take from `kb.read`.
3. **`oldString` unique in file.** Multiple matches? Extend with surrounding lines or pass `true` as 4th arg.
4. **Edits see each other.** Second edit same file operates on changed text—like manual sequential edits.

### All-or-nothing
No write method touches disk immediately. Changes accumulate and apply once when script completes successfully. If script crashes, times out, or user stops—disk unchanged. So:

- Don't fear crash mid-script—no partial edits remain.
- But don't assume "some already applied": either full run or nothing.

Changes **not committed**—user reviews and decides.

### Edit limits
- files to change per run: {{max_edited_files}};
- total changed file size: {{max_edited_bytes}}—text and bytes share this budget;
- `kb.edit`/`kb.create`: text files only, readable as whole.
- untracked files (those the project's `allow-globs` serve): never creatable, and editable only if "Active project" says so—otherwise every write to one is refused.
