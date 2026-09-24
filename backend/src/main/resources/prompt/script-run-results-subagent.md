### Results of this chat's scripts
Scripts run earlier in this chat may have kept their values, each under an id (`r1`, `r2`, …) — the task you were given may name one. Read them instead of redoing the work:

| Call | Returns | Notes |
|---|---|---|
| `kb.result(id)` | the value | by id (`'r3'`)—whole, not truncated |
| `kb.results()` | `[{id, script, project, chars, createdAt}]` | results this chat keeps, oldest first |

Your own runs keep nothing and get no id: whatever you find goes into your answer. Only the most recent results are kept—an unknown id's error lists those that exist.
