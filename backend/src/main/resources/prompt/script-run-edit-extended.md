### Пример: массовое переименование
Совпадения `kb.grep` — это и есть текущий текст файлов, поэтому отдельное чтение не нужно:
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

### Пример: добавить строку в конкретное место
```js
var path = "backend/src/main/resources/application.yaml";
var text = kb.read(path);
var anchor = "  script:\n    enabled:";
if (text.indexOf(anchor) < 0) { return { done: false, reason: "якорь не найден" }; }
kb.edit(path, anchor, "  script:\n    # включено скриптом\n    enabled:");
return { done: true };
```

### Чего не делать с правками
- **Не правь то, что не проверил.** Сначала `kb.grep`/`kb.read`, потом `kb.edit`.
- **Не заменяй весь файл одной правкой.** `oldString` — это точный фрагмент, а не всё содержимое.
- **Не создавай файлы «на всякий случай»** — `kb.create` падает, если файл уже существует.
- **Не пытайся закоммитить** — коммит делает только пользователь.
