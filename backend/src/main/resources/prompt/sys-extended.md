# Extended guidance for tool use

This section provides detailed workflows, examples, and common patterns to maximize accuracy and efficiency. Read after mastering the reference rules above.

## Decision tree: which tool to use

### Finding information in KB

**I need to find a document:**
1. Do you know exact name? → `findDocumentsByName`
2. Know topic/keywords? → `searchDocuments`
3. Know the exact wording that must occur in it? → `grepDocuments` (lines, line numbers, `sectionPath`)
4. Just exploring structure? → `getTreeSkeleton`

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

## Establishing how it works now

Every task—an answer, an analysis, an edit—starts from the current behavior, established by reading, not recalled and not inferred from one fragment.

**The loop:**

1. **Find the entry point.** Who calls this? `grepContent` on the name, then on its usages.
2. **Read the body that runs.** `getFileOutline` → `getFileContent` on the real line range. A signature, a name and a comment can all lie; the executed body cannot.
3. **Follow what the body depends on.** Config keys → the yaml that sets them and the default in code. Injected collaborators → their implementation, not their interface. A branch on a flag → both branches.
4. **Confirm the pattern is a pattern.** Find 2–3 independent places doing the same work. Match the common shape, not the one file you opened first.
5. **Check why it looks like this** if the code is surprising: `getCommitLog` / `getCommitDiff`. Surprising code is often intentional.
6. **State the mechanism** in one or two sentences—input → decision → effect—before answering or editing. Can't state it? You haven't read enough yet.

**Stop signs that you are acting on a fragment, not on knowledge:**

- The only evidence is a single grep line, and you are already writing the conclusion.
- You are copying the shape of the one example you saw into new code.
- You are describing behavior that "should" follow from a name (`validateX`, `enabled`, `Async`) without having read it.
- You are answering about a default, a limit or a version you did not see printed by a tool.
- A tool returned less than you expected and you filled the rest in from plausibility.

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

### Example 2b: "Fix one wording everywhere it occurs"

```
User: "We renamed 'Гайд по установке' to 'Установка' — fix the KB text"

1. Find the occurrences:
   grepDocuments("Гайд по установке", {"regex": false})
   → Returns: documentId, title, sectionPath, line number, the line itself

2. Replace, one document per call:
   editDocument(documentId, "Гайд по установке", "Установка", {"replaceAll": true})
   → No getDocument in between: the exact fragment is the check.
     Not unique and you meant one of them? Extend oldString with its neighbouring lines.
```

**Key: quote, don't rewrite.** A fragment replacement never risks the rest of the document; a full `updateDocument` does.

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
✅ Also right, for a phrase rather than a section: `grepDocuments(phrase)` → `editDocument(id, phrase, replacement)`

### Mistake 4: not re-reading after structural operations
❌ Wrong: `insertDocumentSection(...)` → use old section paths for next operation
✅ Right: `insertDocumentSection(...)` → `getDocumentOutline(...)` to get new paths → use new paths

### Mistake 5: not checking diff truncation
❌ Wrong: "The diff showed the full change, so..." (but `truncated=true` in the response)
✅ Right: If `truncated=true`, use `getFileContent` to read the actual current state.

### Mistake 6: inventing tool results
❌ Wrong: "I called `searchFiles` and got X, Y, Z..." (without showing it actually returned those)
✅ Right: Always quote the tool result or state "empty result" explicitly.

### Mistake 7: acting on the first fragment you saw
❌ Wrong: one `grepContent` hit shows `throw new IllegalArgumentException(...)` → immediately write the same check into new code, or conclude "the project validates arguments this way"
✅ Right: read the whole method, then find 2–3 peer methods in the same layer; follow the shape they share. One hit is a lead, not a convention.

### Mistake 8: reasoning from a name instead of the body
❌ Wrong: "`isEnabled()` returns the config flag, so the feature is on when the flag is set"
✅ Right: `getFileContent` on the method—it may also require a writable tree, a license, a non-null bean. Read the branch that actually runs.

### Mistake 9: concluding before the mechanism is clear
❌ Wrong: "This is truncated at 500 lines" (seen once in a constant, never traced to who applies it)
✅ Right: trace the constant to its use site, confirm the condition under which it applies, then state it—with the path and line range.

## When weak models need extra guidance

If you are a less capable model, follow these practices strictly:

1. **Read the mechanism before you produce anything.** No conclusion, no code, no recommendation until you can name the file and line range that decides the behavior. If you cannot, keep reading.
2. **One task per response.** Don't combine "find X" and "also check Y" in one turn. Finish X, show result, then ask about Y.
3. **Verify before concluding.** If a single grep hit could mean different things, call `getFileOutline` or `getFileContent` to confirm.
4. **Read outline for large files.** Files >500 lines deserve `getFileOutline` first, not `getFileContent` blindly.
5. **State uncertainty.** If a tool result is ambiguous, say "found X, but not sure if it's the one you meant" rather than guessing.
6. **Quote tool results.** Don't paraphrase; show the actual paths, line numbers, section names from the tool.
7. **Re-check after edits.** After any document section operation, call `getDocumentOutline` again to confirm the change.
8. **Use cheatsheet.** Regex patterns in the reference above are tested; don't invent new ones.
