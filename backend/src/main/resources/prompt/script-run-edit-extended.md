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

### What not to do
- **Don't edit unverified files.** `kb.grep`/`kb.read` first, then `kb.edit`.
- **Don't replace entire file in one edit.** `oldString` is exact fragment, not everything.
- **Don't create files speculatively.** `kb.create` fails if file exists.
- **Don't try to commit.** User commits only.
