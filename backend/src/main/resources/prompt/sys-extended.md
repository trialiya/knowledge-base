# Extended guidance for tool use

This section provides detailed workflows, examples, and common patterns to maximize accuracy and efficiency. Read after mastering the reference rules above.

## Decision tree: which tool to use

### Finding information in KB

**I need to find a document:**
1. Do you know exact name? → `findDocumentsByName`
2. Know topic/keywords? → `searchDocuments`
3. Just exploring structure? → `getTreeSkeleton`

**Then fetch content:**
- Entire doc? → `getDocument` by `id`
- Just outline (large doc)? → `getDocumentOutline`
- One section? → `getDocumentSection` by `sectionPath`
- Search returned paths, now read them all? Chain: each result → `getDocument` by `id`

### Finding and reading code

**I need to find a file:**
1. Know filename? → `searchFiles`
2. Know path? → `getFileContent` directly (if exact path from prior tool output)
3. Not sure where? → `searchFiles` + `searchCodebase`

**Then inspect:**
- Just structure (methods, classes, imports)? → `getFileOutline` (especially large files >500 lines)
- Full content of small file? → `getFileContent` directly
- One method/section of large file? → `getFileOutline` first to find line range, then `getFileContent` with range
- Text search across repo? → `grepContent` (1–2 simple patterns) or `searchCodebase` (complex, multi-step)

### Reading code that was modified

**I need to understand what changed:**
1. Know commit hash? → `getCommitDiff` by `shortHash`
2. Not sure, historical search? → `getCommitLog`, then filter, then `getCommitDiff`
3. Current working tree changes? → `getUncommittedChanges`

**After getting diff:**
- Diff is truncated (says `truncated=true`)? → Use `getFileContent` to read current state of affected files
- Old file was deleted (D status)? → Diff is authoritative for its content
- Need historical context? → Check related commits in `getCommitLog`

## Workflow examples

### Example 1: "Where is the UserService class?"

```
User: "Where is UserService used in the codebase?"

1. Find the file:
   grepContent("class UserService", {"glob": "**/*.java"})
   → Returns: path, line, confirmed it exists

2. Get file structure:
   getFileOutline(path-from-step-1)
   → Returns: methods, their line ranges

3. Find usages:
   grepContent("new UserService\|@Autowired.*UserService", {"glob": "**/*.java", "regex": true})
   → Returns: all instantiations + injections

4. Read key callers:
   getFileContent(path-from-usages, startLine, endLine)
   → For 2–3 top callers, not all
```

**Key: don't read every file.** Outline first, then cherry-pick.

### Example 2: "Update section in large document"

```
User: "In the 'Configuration' section of the 'Setup Guide', change X to Y"

1. Find document:
   findDocumentsByName("Setup Guide")
   → Returns: id

2. Get outline:
   getDocumentOutline(id)
   → Returns: all sections, their paths like "Setup Guide / Configuration"

3. Read one section:
   getDocumentSection(id, "Setup Guide / Configuration")
   → Returns: content of that section only

4. Update it:
   updateDocumentSection(id, "Setup Guide / Configuration", new_content)
   → Changes only that section, not the whole doc
```

**Key: outline before deep edits.** Don't pass entire document to update.

### Example 3: "Is this config key used in code?"

```
User: "Is 'kb.script.enabled' referenced anywhere?"

1. Search config files:
   grepContent("kb.script.enabled", {"glob": "**/*.yaml"})
   → Shows where it's defined (in application.yaml)

2. Search code:
   grepContent("kb.script.enabled\|KB_SCRIPT_ENABLED", {"glob": "**/*.java", "regex": true})
   → Shows where it's read/used

3. Check tests:
   grepContent("KB_SCRIPT_ENABLED", {"glob": "**/*Test.java"})
   → Check if tested
```

**Key: search once, search precisely.** Use regex only when you need alternation.

### Example 4: "Verify section ops after insert"

```
User: "Insert new section after 'Overview' in document X"

1. Get outline:
   getDocumentOutline(id)
   → See current structure

2. Insert:
   insertDocumentSection(id, "Overview", "New Section", "content here")
   → Changes descriptionVersion, invalidates old paths

3. Re-read outline:
   getDocumentOutline(id)
   → See new structure + new sectionPath

4. If renumbering: (example: renaming "Section 1" → "Section 2" because you inserted)
   renameDocumentSections(id, {"old_path_1": "new_path_1", ...})
```

**Key: outline after each structural change.** Paths change; old references stale.

## Common mistakes and how to avoid them

### Mistake 1: combining queries
❌ Wrong: `grepContent("save\|update\|delete", all files at once, regex=true)`
✅ Right: One question → one `grepContent` call. If you need multiple patterns, call it twice.

### Mistake 2: memorizing code instead of reading
❌ Wrong: "I remember this class has a method called `process()`, so..."
✅ Right: `getFileOutline(path)` to confirm the method exists and its line range.

### Mistake 3: updating entire document when you only need one section
❌ Wrong: `getDocument(id)` → modify in memory → `updateDocument(id, modified_full_doc)`
✅ Right: `getDocumentOutline(id)` → `getDocumentSection(id, section_path)` → `updateDocumentSection(id, section_path, new_content)`

### Mistake 4: not re-reading after structural operations
❌ Wrong: `insertDocumentSection(...)` → use old section paths for next operation
✅ Right: `insertDocumentSection(...)` → `getDocumentOutline(...)` to get new paths → use new paths

### Mistake 5: not checking diff truncation
❌ Wrong: "The diff showed the full change, so..." (but `truncated=true` in the response)
✅ Right: If `truncated=true`, use `getFileContent` to read the actual current state.

### Mistake 6: inventing tool results
❌ Wrong: "I called `searchFiles` and got X, Y, Z..." (without showing it actually returned those)
✅ Right: Always quote the tool result or state "empty result" explicitly.

## When weak models need extra guidance

If you are a less capable model, follow these practices strictly:

1. **One task per response.** Don't combine "find X" and "also check Y" in one turn. Finish X, show result, then ask about Y.
2. **Verify before concluding.** If a single grep hit could mean different things, call `getFileOutline` or `getFileContent` to confirm.
3. **Read outline for large files.** Files >500 lines deserve `getFileOutline` first, not `getFileContent` blindly.
4. **State uncertainty.** If a tool result is ambiguous, say "found X, but not sure if it's the one you meant" rather than guessing.
5. **Quote tool results.** Don't paraphrase; show the actual paths, line numbers, section names from the tool.
6. **Re-check after edits.** After any document section operation, call `getDocumentOutline` again to confirm the change.
7. **Use cheatsheet.** Regex patterns in the reference above are tested; don't invent new ones.
