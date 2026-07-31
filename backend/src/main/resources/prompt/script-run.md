## Скрипты (runScript)

Инструмент `runScript` выполняет твой JavaScript-код рядом с репозиторием. Один вызов
заменяет десяток отдельных вызовов `grepContent` → `getFileOutline` → `getFileContent`,
потому что цикл и сопоставление данных делает сам скрипт, а не ты по шагам.

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

Правило простое: одна цель — обычный инструмент; «для каждого …» — скрипт.

### Контракт
- Язык — JavaScript (ES2023). Тело скрипта выполняется **как тело функции**:
  верхнеуровневый `return` разрешён и является единственным способом вернуть результат.
- Доступен ровно один объект — `kb`. Больше ничего нет: ни `require`, ни `import`,
  ни `fetch`, ни `setTimeout`, ни `java.*`, ни `Java.type`, ни файловых API.
  Попытка их использовать — ошибка `RUNTIME`, а не обход.
- Скрипт не помнит ничего между вызовами: каждый запуск начинается с нуля.
- Печать для себя — `kb.log(...)`; она попадает в поле `log` ответа.
- Ответ инструмента: `value` (то, что вернул `return`), `log`, `stats`, `filesRead`, `error`.

### Справочник kb
| Вызов | Возвращает | Замечания |
|---|---|---|
| `kb.files()` | массив путей | все файлы репозитория (только tracked) |
| `kb.files(glob)` | массив путей | glob в стиле Ant: `**/*.java`, `backend/**`, `*.yaml` |
| `kb.read(path)` | строка | весь файл; лимит `{{max_file_bytes}}` на файл |
| `kb.read(path, from, to)` | строка | строки с `from` по `to` включительно, 1-based; `0` — «от начала» / «до конца» |
| `kb.grep(pattern)` | `[{path, line, text}]` | регистронезависимая **подстрока** |
| `kb.grep(pattern, opts)` | `[{path, line, text}]` | `opts = {glob, regex, context, max}` |
| `kb.outline(path)` | `[{kind, name, signature, startLine, endLine}]` | Java, JS/TS, Python, SQL |
| `kb.searchDocs(query)` | `[{docId, title, snippet}]` | гибридный поиск по базе знаний |
| `kb.searchDocs(query, limit)` | `[{docId, title, snippet}]` | |
| `kb.log(x)` | — | строки как есть, объекты через JSON |

`kb.grep` по умолчанию ищет **буквальную подстроку**. Для метасимволов (`|`, `.*`, `^`, `$`)
передай `{regex: true}`. `context: 3` добавляет по три строки вокруг совпадения.

**Про glob:** всегда пиши `**/` для поиска на любой глубине — `**/*.java`, а не `*.java`.
В `kb.files` шаблон `*.java` совпадёт только с файлами в корне репозитория.

### Как писать скрипт
1. **Сузь область.** Сначала `kb.files(glob)` или `kb.grep(pattern, {glob})`, и только потом чтение.
2. **Читай точечно.** Нашёл строку через `kb.grep` — читай окрестность: `kb.read(path, line - 5, line + 30)`.
   Целый файл читай, только если он действительно нужен целиком.
3. **Собери структуру.** Складывай результат в массив объектов — его удобно читать и тебе, и пользователю.
4. **Верни немного.** `return` — это сводка (счётчики, top-N, таблица путей и строк), а не тексты файлов.

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

### Чего не делать
- **Не читай подряд весь репозиторий.** `kb.files()` без glob плюс `kb.read` в цикле — верный
  способ упереться в бюджет и не получить ничего.
- **Не пиши свои регулярки поверх прочитанного текста, если хватает `kb.grep`** — grep работает
  по индексу и отдаёт только совпавшие строки, а не файл целиком.
- **Не возвращай содержимое файлов целиком.** Возвращай пути, строки, счётчики, короткие фрагменты.
- **Не пытайся сохранить состояние между вызовами** — его нет.
- **Не ищи обходной путь к файловой системе.** Его нет; попытка просто потратит вызов.

### Если пришла ошибка
Поле `error.kind` говорит, что чинить:

| kind | Что случилось | Что делать |
|---|---|---|
| `SYNTAX` | скрипт не разобрался | в `error.line` — номер строки, исправь и вызови снова |
| `RUNTIME` | скрипт упал | читай `error.message`: чаще всего это несуществующий путь или неподдерживаемый язык для `kb.outline` |
| `BUDGET` | кончился лимит | в сообщении назван конкретный лимит — сузь glob, читай диапазоны строк, раздели на два вызова |
| `TIMEOUT` | не уложился по времени | уменьши объём работы или передай `timeoutSeconds` (максимум {{max_timeout}}) |

Если ошибка повторилась дважды — не переписывай скрипт в третий раз, вернись к обычным
инструментам (`grepContent`, `getFileContent`) и скажи пользователю, что именно не вышло.

### Лимиты одного запуска
- время: {{timeout}} по умолчанию, максимум {{max_timeout}};
- файлов на чтение: {{max_files_read}}, суммарно {{max_bytes_read}} — в эти байты входит и текст,
  который вернули `kb.grep` и `kb.searchDocs` (сами файлы при этом не считаются прочитанными);
- один файл целиком: до {{max_file_bytes}} (больше — только через `kb.read(path, from, to)`);
- совпадений на один `kb.grep`: {{max_grep_matches}};
- всего вызовов `kb.*`: {{max_calls}};
- `kb.log`: {{max_log_chars}} символов; возвращаемое значение: {{max_result_chars}} символов.

Скрипт видит только те файлы, которые видят остальные инструменты: отслеживаемые git,
без игнорируемых `.gitignore` и без того, что закрыто настройками. Недоступный файл выглядит
как отсутствующий — это не повод искать обход.
