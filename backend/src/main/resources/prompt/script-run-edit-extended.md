### Example: bulk rename
`kb.grep` matches are current file text—no separate read needed:
```js
var hits = kb.grep("OldServiceName", { glob: "**/*.java" });
var files = {};
for (var i = 0; i < hits.length; i++) { files[hits[i].path] = true; }

var changed = [];
for (var path in files) {
  kb.edit(path, "OldServiceName", "NewServiceName", true);
  changed.push(path);
}
return { files: changed.length, paths: changed };
```

### Example: insert line at specific location
```js
var path = "backend/src/main/resources/application.yaml";
var text = kb.read(path);
var anchor = "  script:\n    enabled:";
if (text.indexOf(anchor) < 0) { return { done: false, reason: "anchor not found" }; }
kb.edit(path, anchor, "  script:\n    # enabled by script\n    enabled:");
return { done: true };
```

### Example: patch a byte in a binary fixture
```js
var path = "backend/src/test/resources/fixtures/sample.bin";
var bytes = kb.readBytes(path);
if (bytes[0] !== 0x89) { return { done: false, reason: "not the expected header" }; }
bytes[4] = 2;                       // bump the version byte
kb.writeBytes(path, bytes);         // whole content, always
return { done: true, bytes: bytes.length };
```

### What not to do
- **Don't invent `oldString`.** Quote it from `kb.grep`/`kb.read` output; a fragment from memory either misses or hits the wrong place. `kb.writeBytes` needs the read itself—it replaces everything.
- **Don't replace entire file in one `kb.edit`.** `oldString` is exact fragment, not everything—whole-content replace exists only for bytes.
- **Don't `kb.edit` a binary file.** No exact-match anchor there: `kb.writeBytes` or nothing.
- **Don't create files speculatively.** `kb.create`/`kb.createBytes` fail if file exists.
- **Don't try to commit.** User commits only.
