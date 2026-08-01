### Script vs standard tool
| Task | Tool |
|---|---|
| Exact match, 1–2 queries | `grepContent` |
| Read one file or fragment | `getFileContent` |
| One file structure | `getFileOutline` |
| **Iterate many files, count/compare/verify** | `runScript` |
| **Build "file → found" table across repo** | `runScript` |
| **Cross-reference sources** (code vs config, code vs doc) | `runScript` |
| Broad ambiguous "how does it work" search | `searchCodebase` |

### How to write scripts
1. **Start with filtering.** `kb.files(glob)` or `kb.grep(pattern, {glob})` first, read next—not for token budget (sufficient for full repo) but less work, faster answer.
2. **Read precisely.** Found line via grep? Read context: `kb.read(path, line - 5, line + 30)`. Read whole file when truly needed—normal, not last resort.
3. **Build structure.** Collect results in object array—easy to read, return, use.
4. **Return summary.** `return` is counts, top-N, path/line table—not file contents.

### Examples

Find all classes implementing interface, return table:
```js
var hits = kb.grep("implements ToolCallResponseItem", { glob: "**/*.java" });
var rows = [];
for (var i = 0; i < hits.length; i++) {
  var h = hits[i];
  var name = h.path.split("/").pop().replace(".java", "");
  rows.push({ file: h.path, type: name, line: h.line });
}
return { count: rows.length, rows: rows };
```

Verify config key is used in code:
```js
var keys = kb.grep("kb.script", { glob: "**/application*.yaml" });
var out = [];
for (var i = 0; i < keys.length; i++) {
  var key = keys[i].text.trim().split(":")[0].trim();
  var usages = kb.grep(key, { glob: "**/*.java" });
  out.push({ key: key, usedIn: usages.length });
}
return out;
```

Gather TODO/FIXME with context:
```js
var hits = kb.grep("TODO|FIXME", { regex: true, context: 1, max: 100 });
return hits.map(function (h) {
  return { path: h.path, line: h.line, text: h.text.trim().slice(0, 120) };
});
```

Cross-reference: all tool methods in code vs mention in prompts:
```js
var declared = kb.grep("@Tool(", { glob: "**/functions/*.java", context: 0 });
var files = {};
for (var i = 0; i < declared.length; i++) { files[declared[i].path] = true; }
var names = [];
for (var path in files) {
  var symbols = kb.outline(path);
  for (var j = 0; j < symbols.length; j++) {
    if (symbols[j].kind === "method") { names.push(symbols[j].name); }
  }
}
var prompt = kb.read("backend/src/main/resources/prompt/sys.md");
var missing = names.filter(function (n) { return prompt.indexOf(n) < 0; });
return { total: names.length, missingInPrompt: missing };
```

Find largest files in directory:
```js
var paths = kb.files("backend/src/main/java/**/*.java");
var sizes = [];
for (var i = 0; i < paths.length && i < 100; i++) {
  var lines = kb.read(paths[i]).split("\n").length;
  sizes.push({ path: paths[i], lines: lines });
}
sizes.sort(function (a, b) { return b.lines - a.lines; });
return sizes.slice(0, 10);
```

Outline large file, read only target method:
```js
var symbols = kb.outline("backend/src/main/java/io/github/trialiya/kb/service/GitService.java");
var target = symbols.filter(function (s) { return s.name === "editFile"; })[0];
if (!target) { return { found: false }; }
return {
  found: true,
  startLine: target.startLine,
  body: kb.read("backend/src/main/java/io/github/trialiya/kb/service/GitService.java",
                target.startLine, target.endLine)
};
```

Link code and KB docs:
```js
var docs = kb.searchDocs("export documents", 5);
var out = [];
for (var i = 0; i < docs.length; i++) {
  var words = docs[i].title.split(" ")[0];
  out.push({ doc: docs[i].title, docId: docs[i].docId, codeHits: kb.grep(words).length });
}
return out;
```

### Pitfalls
- **Don't read unneeded files.** Traversing whole repo is fine and sometimes needed—budget sufficient. Pointless: read whole file for one line.
- **Don't apply regex over read text if `kb.grep` works.** Grep is indexed, returns only matched lines, not file.
- **Don't return raw file contents.** Paths, lines, counts, short snippets.
- **Don't keep state between calls.** None exists.
- **Don't seek filesystem workarounds.** None; attempts just waste calls.
