# Отображение результатов вызова инструментов в модалке

Предложение по формату `ToolCallDetailModal`. Инвентарь того, что реально возвращают
36 инструментов, разбор почему одного JSON недостаточно, и формат модалки, который
покрывает весь набор без свича на 36 веток.

## 1. Что показывается сейчас

`ToolCallDetailModal` (`frontend/src/components/chatPanel/`) грузит
`GET /api/chats/{id}/tool-calls?callId=…` → `ToolCallDetail` и рисует три секции:

| Секция | Источник | Как выведено |
| --- | --- | --- |
| Статус | `status` | бейдж OK / ERROR / STARTED |
| Аргументы | `argumentsRaw` | `JSON.stringify(parse(x), null, 2)` + подсветка регуляркой |
| Результат | `resultText` | то же, если парсится как JSON; иначе — как есть |
| Ошибка | `error` | `pre` |

`resultText` — это **дословно то, что ушло модели**: `tool_data.responses[].responseData`,
то есть выход `DefaultToolCallResultConverter`, то есть Jackson-JSON возвращённого DTO.
Никакой отдельной «версии для человека» не сохраняется.

Отсюда и проблема. Возьмём три реальных вызова из `sample-data.sql` и из типового прогона:

```jsonc
// getCommitDiff — один вызов, ~40 КБ в одну строку
[{"hash":"33195a2…","author":"Trialiya","files":[{"status":"A","path":"backend/build.gradle",
  "additions":79,"deletions":0,"patch":"diff --git a/backend/build.gradle …\n+plugins {\n+  id …
```

```jsonc
// getFileContent — весь файл живёт внутри JSON-строки
{"path":"backend/…/GitFunction.java","content":"package io.github…;\n\nimport …","binary":false,
 "sizeBytes":11542,"language":"java","lineCount":323,"truncated":false,"fromLine":null,"toLine":null}
```

```jsonc
// recordChatInsights — на весь экран разворачивается вот это
"Done"
```

Три отдельные болячки:

1. **Содержимое файлов и diff'ы — внутри JSON-строки.** Переносы строк как `\n`,
   кавычки экранированы, подсветка `.tcd-pre` красит это одним зелёным «строковым»
   цветом. Diff, который в `FileDiffModal` уже умеет краситься по `+`/`-`/`@@`,
   в модалке инструмента показан плоским текстом с `\n`.
2. **Полезное тонет в служебном.** У `getFileContent` из девяти полей человеку нужны
   `path`, `language`, диапазон строк и, собственно, `content`; остальное — шум,
   занимающий первый экран.
3. **Скалярные результаты занимают столько же места, сколько 40 КБ diff'а.** Одинаковая
   секция `pre` для `"Done"` и для дерева документов.

Плюс к тому же **аргументы болеют тем же самым**: у `editFile` в `argumentsRaw` лежит
целиком новый фрагмент файла, у `createDocument` — целиком markdown документа.
Разбирать их построчно в `\n`-экранированном виде так же неудобно.

## 2. Что уже есть и переиспользуется

Не нужно писать с нуля — половина рендереров в проекте уже написана под другие места:

- `FileChangeBlock.jsx` → `FileDiffModal` — раскраска unified diff (`diffLineClass`,
  классы `file-diff-line--add/del/hunk` в `styles/file-changes.css`) и тумблер
  markdown-превью для `.md`.
- `DocChangeBlock.jsx` — карточка документной мутации со ссылкой в историю версий.
- `toolMeta.js` — `getFileChangeRefs` / `getDocChangeRef`: уже умеет доставать из
  `resultMeta` файловые и документные правки, включая пачку `runScript.edits`.
- `toolNames.js` — реестр `TOOL_META` с иконкой и категорией на инструмент.
- `ReactMarkdown` + `remarkGfm` уже в зависимостях и уже используются в чате.

То есть задача не «нарисовать вьюверы», а **выбрать вьювер по форме данных** и
довести три-четыре недостающих.

## 3. Инвентарь: 36 инструментов → 8 форм результата

Собрано по `@Tool`-методам пакета `functions` и их возвращаемым типам.

### A. Текст файла / вложения (4)

| Инструмент | Возвращает | Где текст |
| --- | --- | --- |
| `getFileContent` | `GitFileContent` | `content` + `language`, `lineCount`, `fromLine`/`toLine`, `truncated`, `binary` |
| `getAttachmentContent` | `String` | весь результат — голый текст, даже не JSON-объект |
| `getAttachmentContentByFileName` | `List<AttachmentContext>` | `content` у каждого элемента |
| `getDocument` | `DocumentNode` | `description` — весь markdown документа |

Самый частый и самый болезненный случай. Нужно: моноширинный блок с номерами строк,
смещёнными на `fromLine`, подсветкой по `language` и шапкой «путь · язык · строки».

### B. Unified diff (4)

| Инструмент | Возвращает | Где diff |
| --- | --- | --- |
| `getCommitDiff` | `List<GitCommit>` | `files[].patch` — вложен на два уровня |
| `getUncommittedChanges` | `List<GitDiffEntry>` | `patch` |
| `createFile` / `editFile` | `GitEditResult` | `diff` (у `create` — `null`) |
| `runScript` | `ScriptResult` | `edits[].diff` |

Нужно: ровно `FileDiffModal`, вынесенный в переиспользуемый `<DiffView>`, плюс
группировка по файлу и счётчики `+N/−M`, которые в DTO уже посчитаны
(`additions`/`deletions`).

### C. Список однотипных записей (10)

| Инструмент | Элемент |
| --- | --- |
| `searchDocuments` | `SearchResult` (`title`, `snippet`, `parentList`, `updatedAt`) |
| `findDocumentsByName` | `DocumentNode` |
| `getDocumentAttachments`, `getChatAttachments`, `searchAttachments` | `Attachment` (`fileName`, `contentType`, `fileSize`, `summary`) |
| `getFileTree`, `searchFiles` | `GitFileNode` (`path`, `type`, `size`) |
| `getCommitLog` | `GitCommit` (`shortHash`, `author`, `date`, `message`) |
| `grepContent` | `GitGrepMatch` (`path`, `matchLine`, `text`) |

Нужно: строка на элемент, важные поля в строке, остальное — по развороту. Для
`grepContent` — своя раскраска: формат `text` уже размечен (`:85:` — совпадение,
`-86-` — контекст, см. javadoc `GitGrepMatch`).

### D. Дерево / оглавление (3)

| Инструмент | Форма |
| --- | --- |
| `getTreeSkeleton` | `List<DocumentNode>` с рекурсивным `children` |
| `getDocumentOutline` | `DocumentOutline.sections[]` — плоский список с `level` 1–6 и `path` |
| `getFileOutline` | `GitFileOutline.symbols[]` — `kind`, `name`, `signature`, `startLine`–`endLine` |

Нужно: отступ по уровню (`level` / вложенность / `kind`), сворачивание ветвей.
`getFileTree` тоже сюда просится — плоские пути собираются в дерево на клиенте.

### E. Markdown / отчёт (3)

| Инструмент | Где текст |
| --- | --- |
| `getDocumentSection` | `content` — markdown секции с заголовком |
| `searchCodebase` | `SearchAgentResult.report` — отчёт саб-агента с цитатами `path:line` |
| `getOriginalMessages` | `String` — склеенные сообщения чата |

Нужно: то же, что A, но с тумблером «исходник ↔ рендер» (в `FileDiffModal` он уже есть
для `.md`). Для `searchCodebase` полезно превратить `path:line` в ссылки на файлы.

### F. Мутация документа (7)

`createDocument`, `updateDocument`, `updateDocumentSection`, `insertDocumentSection`,
`deleteDocumentSection`, `renameDocumentSections` → `DocumentShort`;
`copyAttachmentToDocument` → `String`.

Нужно: карточка «что изменилось» — заголовок, тип, версия, `descriptionVersion` — и
кнопка в историю версий. Ровно `DocChangeBlock`, только на один вызов.

### G. Прогон скрипта (1)

`runScript` → `ScriptResult { value, log[], stats, error, filesRead[], edits[] }` — единственный
составной результат: числовая статистика (`ScriptStats`: файлы, байты, вызовы, мс),
лог `kb.log`, ошибка с видом и строкой (`ScriptError.Kind`), список прочитанных путей
и пачка правок с diff'ами. В JSON это худший из всех случаев: diff'ы, лог и стата в одной
простыне.

Нужно: плитки статистики + лог + список правок, каждая с `<DiffView>` (форма B).

### H. Скаляр (4)

`getChatId`, `getUserName`, `getCurrentDateTime` → `String`; `createAttachment` → `long`;
`recordChatInsights` → `void` («Done»).

Нужно: одна строка в шапке результата, без секции `pre` вообще.

### I. Всё остальное — MCP

MCP-инструменты приходят из внешних серверов, форма произвольная и заранее неизвестна.
Любое решение, завязанное на перечисление имён, их не покрывает — им нужен работающий
фолбэк, а не «показываем сырой JSON».

## 4. Предложение

### 4.1 Два режима на секцию результата

Сегментированный переключатель в заголовке секции, рядом с кнопкой копирования:

```
Результат                                  [ Обзор │ JSON ]   ⧉
```

- **Обзор** — типизированный рендер (раздел 4.3). Режим по умолчанию, если для формы
  нашёлся рендерер.
- **JSON** — `resultText`, отформатированный и подсвеченный. Единственный режим,
  когда обзора для формы нет, — тогда переключателя нет вовсе.

Отдельного «сырого» режима не нужно: на неразбираемом ответе JSON-режим печатает
исходную строку как есть, так что вход модели виден всегда — а это и было
единственное, ради чего третий режим заводился.

Выбранный режим держится в состоянии модалки (не в URL — модалка не адресуемая) и
сбрасывается на дефолт при смене `callId`.

Ту же пару со временем получает секция **Аргументы** — по тем же причинам (см.
`editFile`, `createDocument`), только «Обзор» для аргументов — это плоский список
`имя: значение` с правилом 4.2 для длинных значений.

### 4.2 Универсальное правило: длинная строка — не значение, а блок

Одно правило, которое чинит бо́льшую часть боли **до** всякой типизации и работает для
MCP тоже. При отрисовке любого JSON-узла:

> Строковое поле, содержащее `\n` или длиннее ~200 символов, не печатается как
> значение в строке. Вместо него — свёрнутый блок с меткой поля, размером
> (`1 240 симв. · 42 строки`) и разворотом в моноширинный вьювер, где переносы
> уже настоящие, а не `\n`.

Внутри такого блока выбирается вьювер по содержимому строки, а не по имени поля:
похоже на unified diff (`@@ -a,b +c,d @@`) → `<DiffView>`; иначе — `<TextView>` с
номерами строк и, если поле называется `content`/`patch`/`description`/`report` или
у соседей есть `language`/`path`, подсветкой.

Этого одного достаточно, чтобы `getCommitDiff`, `getFileContent`, `editFile` и
произвольный MCP-инструмент, вернувший текст файла, стали читаемыми — без единой
записи в каком-либо реестре.

### 4.3 Каталог рендереров: матч по форме, не по имени

Свич по 36 именам не переживёт ни нового `@Tool`, ни MCP. Вместо него — упорядоченный
список представлений, первое подошедшее выигрывает:

```js
// chatPanel/resultViews/registry.js
// { id, match(parsed, toolName) -> bool, Component }
[
  scriptRun,     // объект с stats + log + edits          → runScript
  fileContent,   // строковый content + language|lineCount → getFileContent
  diffList,      // элементы с patch|diff                  → getCommitDiff, getUncommittedChanges, …
  docMutation,   // id + title + descriptionVersion        → 6 документных мутаций
  outline,       // sections[] с level | symbols[] с kind  → getDocumentOutline, getFileOutline
  tree,          // элементы с children[] | path[]         → getTreeSkeleton, getFileTree
  grepMatches,   // элементы с path + matchLine + text     → grepContent
  recordList,    // массив однотипных плоских объектов     → все списочные (форма C)
  markdownText,  // длинная строка / поле content|report   → секции, отчёты, вложения
  scalar,        // строка/число/булево ≤ 200 симв.        → getChatId, recordChatInsights, …
  jsonTree,      // фолбэк, всегда матчится               → MCP и всё незнакомое
]
```

`toolName` — только тай-брейкер, когда формы двух инструментов совпадают
(`createFile` без `diff` vs. голый `GitEditResult`), но не обязательное условие: MCP-инструмент,
вернувший `{path, content}`, честно получит `fileContent`.

Детект — чистые функции в `.js` рядом с компонентами (как `treeOps.js`, `fileChips.js`),
покрываются юнит-тестами на реальных `responseData` из `sample-data.sql`.

### 4.4 Шапка «факты» вместо повторения полей

Над вьювером — одна строка со значимыми полями формы, чтобы не искать их в JSON:

```
backend/…/functions/GitFunction.java   ·   java   ·   строки 59–120 из 323   ·   11.5 КБ
```

Правило то же, что для правой панели в `frontend-ui.md`: метаданные — отдельно,
содержимое — отдельно. Поля, попавшие в шапку, из «Обзора» не дублируются;
полный набор всегда доступен в режиме JSON.

### 4.5 Макеты

**A. Содержимое файла** (`getFileContent`)

```
┌──────────────────────────────────────────────────────────────┐
│ 📄 Содержимое файла                          ✓ OK         ✕  │
├──────────────────────────────────────────────────────────────┤
│ ▸ Аргументы   path: backend/…/GitFunction.java, fromLine: 59 │  ← свёрнуто в строку
├──────────────────────────────────────────────────────────────┤
│ Результат                      [ Обзор │ JSON ]           ⧉  │
│ backend/…/GitFunction.java · java · строки 59–120 из 323     │
│ ┌──────────────────────────────────────────────────────────┐ │
│ │  59 │     @Tool(                                         │ │
│ │  60 │             description = "Возвращает содержимое…" │ │
│ │  61 │     public GitFileContent getFileContent(          │ │
│ └──────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────┘
```

**B. Diff** (`getCommitDiff` — вложенная форма, поэтому список файлов сверху)

```
│ Результат                      [ Обзор │ JSON ]           ⧉  │
│ 33195a2 · Trialiya · 20.05.2026 · Init frontend/backend      │
│ ▾ backend/build.gradle                          +79 / −0     │
│ ┌──────────────────────────────────────────────────────────┐ │
│ │ @@ -0,0 +1,79 @@                                         │ │  ← фиолетовый
│ │ + plugins {                                              │ │  ← зелёный
│ │ +   id 'org.springframework.boot'                        │ │
│ └──────────────────────────────────────────────────────────┘ │
│ ▸ backend/src/main/…/Application.java            +12 / −3    │
```

**C. Список** (`searchDocuments`) — строка на элемент, разворот на детали

```
│ Результат  · 12 записей         [ Обзор │ JSON ]          ⧉  │
│ ▸ 📄 Архитектура            Проект/Разработка   обн. 12.08   │
│      …слои Controller → Service → Repository, AI-advisor…    │
│ ▸ 📄 Обзор чат-системы      Проект             обн. 03.08    │
```

**D. Grep** (`grepContent`) — группировка по файлу, номера строк из разметки `text`

```
│ Результат  · 7 совпадений в 3 файлах  [ Обзор │ JSON ]       │
│ backend/…/ChatMemoryService.java                             │
│   84 │   BROWSE_PATHS_V2_ASPECT_NAME,                        │  ← контекст, приглушённый
│   85 │   SUB_TYPES_ASPECT_NAME,                              │  ← совпадение, подсвеченное
│   86 │   STRUCTURED_PROPERTIES_ASPECT_NAME,                  │
```

**E. Прогон скрипта** (`runScript`)

```
│ Результат                      [ Обзор │ JSON ]           ⧉  │
│ ┌────────┬────────┬────────┬────────┐                        │
│ │ 42     │ 1.2 МБ │ 87     │ 340 мс │                        │
│ │ файла  │ прочит.│ вызова │        │                        │
│ └────────┴────────┴────────┴────────┘                        │
│ ▾ Лог (12 строк)                                             │
│ ▾ Изменённые файлы (3)                                       │
│     ▸ frontend/src/…/Message.jsx           +14 / −2           │
│ ⚠ BUDGET · строка 7 · превышен лимит kb.script.limits.files   │
```

**H. Скаляр** — секции нет вообще, значение в шапке

```
│ Результат                                    ⧉               │
│ Done                                                          │
```

### 4.6 Ограничение объёма

`resultText` в 40 КБ рендерить целиком незачем. Правило на все вьюверы: показываем
первые ~300 строк, дальше — «Показать ещё N строк» / «Показать целиком». Так же, как
`Compact.truncate` ограничивает то, что видит модель, — только граница другая
и решает её пользователь.

## 5. Что нужно от бэкенда

Практически ничего — и это главный аргумент за такой формат.

- `resultText` уже отдаётся целиком; `resultMeta` уже несёт `diff`, `path`, `edits`,
  `descriptionVersion` и прочее, чем пользуются `FileChangeBlock`/`DocChangeBlock`.
- Ни одного нового поля, ни одной миграции. `ToolCallDetail` не меняется.
- Единственное, что стоит рассмотреть отдельным шагом: `resultText` больших вызовов
  (`getCommitDiff` по крупному коммиту) едет по сети целиком. Если это станет заметно —
  добавить `?maxChars=` в `GET /api/chats/{id}/tool-calls` и флаг усечения в ответе.
  До замеров — не трогать.

## 6. Этапы

Каждый этап самостоятелен и полезен сам по себе.

1. ✅ **Текстовые результаты (формы A и E) + два режима (4.1) + шапка фактов (4.4)
   + порог объёма (4.6).** Сделано: `resultViews/contentResult.js` (разбор по форме),
   `ContentResultView.jsx` (номера строк со сдвигом на `fromLine`, тумблер markdown),
   `jsonText.js` (формат и подсветка). Закрывает содержимое файлов, вложений,
   документов и их секций — и заодно любой MCP-инструмент, вернувший текст.
2. **Реестр форм (4.3).** Сейчас вид один и подключён напрямую; со вторым видом
   отбор переезжает в упорядоченный список `{ match, Component }`.
3. **`diffList`** — вынести раскраску из `FileDiffModal` в общий `<DiffView>`
   и подключить к `getCommitDiff` / `getUncommittedChanges` / `editFile` / `runScript`.
4. **`recordList` и `scalar`** — вместе с этапом 3 закрывают 21 из 36 инструментов.
5. **Специализированные:** `outline`, `tree`, `grepMatches`, `docMutation`
   (переиспользуя `DocChangeBlock`), `scriptRun`.
6. **Правило длинной строки (4.2) в JSON-режиме и в аргументах.** Отдельно от «Обзора»:
   чинит формы, у которых своего вида ещё нет, и секцию аргументов (`editFile`,
   `createDocument`).

## 7. Открытые вопросы

- **Ссылки наружу.** Из результата напрашиваются переходы: путь → `/files/<path>`
  (`filesPath` уже есть), `id` документа → `/knowledge/doc/<id>`, `path:line` из
  `searchCodebase` → файл на нужной строке. Модалка при этом должна закрыться —
  нужно решить, закрывает ли она сама себя или открывает во вкладке (`FileDiffModal`
  сейчас делает второе).
- **Подсветка синтаксиса.** `language` в `GitFileContent` есть, библиотеки подсветки
  в зависимостях нет. Либо тянуть её, либо ограничиться номерами строк и моноширинным
  шрифтом. Для чтения diff'а раскраски `+`/`−` хватает; для чтения файла — вопрос.
- **Строки в i18n.** Все новые подписи — в `chat.json` (`en` + `ru`), под
  `toolCall.detail.*`. Названия режимов, «показать ещё», подписи плиток статистики.
- **Тёмная тема.** `tool-call-detail-modal.css` жёстко светлый (`#fff`, `#eee`), при
  этом `.tcd-pre` уже берёт `--kb-code-dark-*`. Новые блоки стоит сразу писать на
  токенах, а не на литералах.
- **Именование классов.** По `frontend-css.md` новых `tcd-`-классов быть не должно:
  блок для новых стилей — `tool-result__*`, и при заходе в файл по правилу
  «migrate on touch» существующие `tcd-` переезжают туда же.
