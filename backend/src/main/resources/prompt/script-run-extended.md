### Когда брать скрипт, а когда обычный инструмент
| Задача | Чем делать |
|---|---|
| Одно точное совпадение, один-два запроса | `grepContent` |
| Прочитать один файл или его фрагмент | `getFileContent` |
| Структура одного файла | `getFileOutline` |
| **Пройтись по многим файлам и что-то посчитать/сопоставить/сверить** | `runScript` |
| **Собрать таблицу «файл → найденное» по всему репозиторию** | `runScript` |
| **Сверить два источника** (код и конфиг, код и документ) | `runScript` |
| Широкий неоднозначный поиск «как это устроено» | `searchCodebase` |

### Как писать скрипт
1. **Начни с отбора.** Сначала `kb.files(glob)` или `kb.grep(pattern, {glob})`, и только потом чтение —
   не ради экономии бюджета (его хватит на весь репозиторий), а потому что так меньше работы и
   быстрее ответ.
2. **Читай точечно.** Нашёл строку через `kb.grep` — читай окрестность: `kb.read(path, line - 5, line + 30)`.
   Целый файл читай, когда он нужен целиком: это нормально, а не крайняя мера.
3. **Собери структуру.** Складывай результат в массив объектов — его удобно читать и тебе, и пользователю.
4. **Верни немного.** `return` — это сводка (счётчики, top-N, таблица путей и строк), а не тексты файлов.
5. **Сравниваешь файл со многим — читай его прямо в цикле, не выноси заранее.** Для задачи «для
   каждого файла — для каждого имени» естественно и правильно писать `kb.read` внутри внутреннего
   цикла, даже если один и тот же файл так читается много раз: повторный вызов с теми же аргументами
   ничего не стоит (см. справочник), так что городить отдельный проход «сначала всё прочитать в
   объект» не нужно — это не про бюджет.

### Примеры

Найти все классы, реализующие интерфейс, и вернуть таблицу:
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

Проверить, что ключ конфига действительно используется в коде:
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

Собрать TODO/FIXME с контекстом:
```js
var hits = kb.grep("TODO|FIXME", { regex: true, context: 1, max: 100 });
return hits.map(function (h) {
  return { path: h.path, line: h.line, text: h.text.trim().slice(0, 120) };
});
```

Сверить два источника — все инструменты в коде против упомянутых в промпте:
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

Найти самые большие файлы в каталоге:
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

Найти структуру большого файла и прочитать только нужный метод:
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

Связать код и базу знаний:
```js
var docs = kb.searchDocs("экспорт документов", 5);
var out = [];
for (var i = 0; i < docs.length; i++) {
  var words = docs[i].title.split(" ")[0];
  out.push({ doc: docs[i].title, docId: docs[i].docId, codeHits: kb.grep(words).length });
}
return out;
```

Проверить список имён против множества файлов — файл называется в обоих циклах, читается он
внутри внутреннего, и это не расточительно: одинаковый вызов `kb.read` на одном и том же файле
отдаётся из кэша прогона (см. справочник kb), а не считается заново:
```js
var usage = {};
for (var i = 0; i < names.length; i++) {
  var name = names[i];
  var refs = [];
  for (var j = 0; j < files.length; j++) {
    if (kb.read(files[j]).indexOf(name) >= 0) { refs.push(files[j]); }
  }
  usage[name] = refs;
}
return usage;
```

### Чего не делать
- **Не читай файлы, которые не нужны задаче.** Обойти весь репозиторий можно и иногда нужно —
  бюджета хватит; бессмысленно другое: читать файл целиком, когда из него нужна одна строка.
- **Не пиши свои регулярки поверх прочитанного текста, если хватает `kb.grep`** — grep работает
  по индексу и отдаёт только совпавшие строки, а не файл целиком.
- **Не возвращай содержимое файлов целиком.** Возвращай пути, строки, счётчики, короткие фрагменты.
- **Не пытайся сохранить состояние между вызовами** — его нет.
- **Не ищи обходной путь к файловой системе.** Его нет; попытка просто потратит вызов.
