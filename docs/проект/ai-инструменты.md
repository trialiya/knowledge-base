# AI-инструменты

> **Связанные документы:** [Введение](введение.md) · [Архитектура](архитектура.md) · [Модели данных](модели-данных/.content.md) · [API Reference](api-reference.md) · [Конфигурация](конфигурация.md) · [Руководство по установке](руководство-по-установке.md)

Система предоставляет AI-ассистенту набор инструментов для работы с базой знаний, Git-репозиторием и системной информацией. Инструменты вызываются через `ToolController` (`POST /api/tools`).

Все инструменты используют **`CompactToolResultConverter`** — конвертер, который сжимает результаты в компактное превью (`resultGist`). Каждый DTO-результат реализует интерфейс `ToolCallResponseItem` с методом `toGist()`, возвращающим краткую сводку.

---

## 1. Поиск и чтение документов

### `searchDocuments`
Гибридный поиск по документам (keyword + semantic). Результаты включают `parentList` — хлебные крошки (предки от корня до родителя).
- **Параметры:** `query` (String), `mode` (hybrid|semantic|keyword), `threshold` (double), `limit` (int), `kwWeight` (double), `semWeight` (double)
- **Возвращает:** список документов с релевантностью и `parentList`

### `findDocumentsByName`
Поиск документов по названию (точное или частичное совпадение).
- **Параметры:** `name` (String)
- **Возвращает:** список документов (сначала точные совпадения)

### `getDocument`
Получение документа по ID с полным содержимым и дочерними узлами.
- **Параметры:** `documentId` (String)
- **Возвращает:** `DocumentDto`

### `getTreeSkeleton`
Получение всей структуры базы знаний (id, title, type, parentId).
- **Параметры:** нет
- **Возвращает:** плоский список всех узлов

---

## 2. Управление документами

### `createDocument`
Создание нового документа или папки.
- **Параметры:** `title` (String), `type` (document|folder), `parentId` (String|null), `description` (String)
- **Возвращает:** созданный документ

### `updateDocument`
Обновление существующего документа (название и/или содержимое).
- **Параметры:** `documentId` (String), `title` (String|null), `description` (String|null)
- **Возвращает:** обновлённый документ

---

## 3. Работа с вложениями

### `searchAttachments`
Поиск по вложениям (файлам) — по имени, содержимому и описанию.
- **Параметры:** `query` (String)
- **Возвращает:** список вложений

### `getAttachmentContent`
Получение полного текстового содержимого вложения по ID.
- **Параметры:** `attachmentId` (String)
- **Возвращает:** текст содержимого

### `getAttachmentContentByFileName`
Получение содержимого вложения по имени файла.
- **Параметры:** `fileName` (String)
- **Возвращает:** текст содержимого

### `getDocumentAttachments`
Список вложений для документа.
- **Параметры:** `documentId` (String)
- **Возвращает:** метаданные вложений

### `getChatAttachments`
Список вложений для текущего чата.
- **Параметры:** нет
- **Возвращает:** метаданные вложений

### `copyAttachmentToDocument`
Копирование вложения из чата в документ базы знаний.
- **Параметры:** `attachmentId` (String), `targetDocumentId` (String)
- **Возвращает:** обновлённое вложение

---

## 4. Работа с Git-репозиторием

### `getFileTree`
Получение дерева файлов репозитория (один уровень).
- **Параметры:** `path` (String|null — корень)
- **Возвращает:** список `GitFileNode`

### `getFileContent`
Получение содержимого файла из репозитория.
- **Параметры:** `filePath` (String), `fromLine` (int|null), `toLine` (int|null)
- **Возвращает:** `GitFileContent` с полями: `path`, `content` (текст или null для бинарных), `binary`, `sizeBytes`, `language` (определяется по расширению), `lineCount` (всего строк), `truncated` (true если вернулась только часть), `fromLine`/`toLine` (фактический диапазон)
- **Для больших файлов (>512 КБ)** без указания диапазона возвращается фрагмент: начало + конец, `truncated=true`

### `getFileOutline`
Структурный анализ файла кода (классы, методы, поля и т.д.).
- **Параметры:** `filePath` (String)
- **Возвращает:** `OutlineResult` — обёртка над `GitFileOutline` со списком `GitSymbol` (имя, `startLine`, `endLine`)
- **Движок:** tree-sitter (Java, TypeScript, Python, SQL) → фолбэк на regex при недоступности tree-sitter. Поле `parser` показывает, какой движок использован (`tree-sitter` или `regex`).

### `searchFiles`
Поиск файлов в репозитории по имени/пути.
- **Параметры:** `pattern` (String), `maxResults` (int, default 20)
- **Возвращает:** список найденных файлов

### `grepContent`
Поиск текста внутри файлов репозитория (git grep).
- **Параметры:** `pattern` (String), `pathGlob` (String|null), `regex` (boolean, default false), `contextLines` (int, default 1), `maxResults` (int, default 50)
- **Возвращает:** список `GitGrepMatch` с полями: `path`, `matchLine` (int — номер строки совпадения), `text` (строка с совпадением)
- **Предупреждение:** если pattern содержит regex-символы (`|`, `.*`, `^`, `$` и др.) но `regex=false` — возвращается предупреждение

### `getCommitLog`
История коммитов.
- **Параметры:** `maxCount` (int, default 20, max 100), `filePath` (String|null)
- **Возвращает:** список `GitCommit`

### `getCommitDiff`
Изменения для указанного коммита.
- **Параметры:** `commitHashes` (String — один или через запятую), `includePatch` (boolean)
- **Возвращает:** список `GitDiffEntry` + diff (опционально)

### `getUncommittedChanges`
Незакоммиченные изменения в рабочей директории.
- **Параметры:** `includePatch` (boolean)
- **Возвращает:** список изменённых файлов + diff (опционально)

---

## 5. Умный поиск (searchCodebase)

### `searchCodebase`
Многошаговый поиск по коду репозитория и базе знаний через сабагента. В отличие от одиночного `grepContent`, сабагент сам итеративно вызывает grep, обзор структуры и чтение файлов, возвращая сжатый отчёт с цитатами `path:line`.

- **Параметры:**
  - `task` (String) — подробная постановка задачи на естественном языке: что искать и зачем. Чем конкретнее (термины, имена классов/методов, область), тем лучше результат.
  - `scope` (String, опционально) — область поиска: `"code"` | `"docs"` | `"all"` (по умолчанию all)
  - `pathGlob` (String, опционально) — glob для ограничения путей в коде, например `"backend/**/*.java"`. null — без ограничения.
- **Возвращает:** `SearchAgentResult` с полями:
  - `report` (String) — сжатый отчёт с цитатами `path:line`
  - `complete` (boolean) — true если сабагент завершил поиск самостоятельно; false если исчерпан бюджет итераций или произошла ошибка
  - `iterations` (int) — количество раундов tool-call
  - `durationMs` (long, скрыто от модели) — длительность в миллисекундах

**Когда использовать `searchCodebase` вместо `grepContent`:**
- `grepContent` — простое точное совпадение, один-два запроса («где вызывается `save()`?»)
- `searchCodebase` — широкий или неоднозначный вопрос, требующий нескольких шагов (grep → структура → чтение фрагментов) и/или объединения кода и документов («как устроена авторизация и где она проверяется?»)

**Ограничения сабагента:**
- Только read-only инструменты (grepContent, searchFiles, getFileTree, getFileOutline, getFileContent, searchDocuments, findDocumentsByName, getDocument, getTreeSkeleton)
- Не может вызывать сам себя (защита от рекурсии через `allowed-tools`)
- Жёсткий лимит итераций (`maxIterations`, по умолчанию 30) — при исчерпании принудительная суммаризация
- Отдельная модель (может отличаться от основной — например, более дешёвая/быстрая)
- Настраивается через `kb.search.subagent.*` в [Конфигурации](конфигурация.md)

---

## 6. Системные инструменты

### `getCurrentDateTime`
Текущие дата и время в часовом поясе пользователя.
- **Параметры:** нет
- **Возвращает:** строка с датой/временем

### `getUserName`
Имя текущего пользователя.
- **Параметры:** нет
- **Возвращает:** имя пользователя

### `getChatId`
ID текущего чата.
- **Параметры:** нет
- **Возвращает:** ID чата

### `getOriginalMessages`
Получение оригинальных сообщений чата по позициям.
- **Параметры:** `positions` (List of int)
- **Возвращает:** полные тексты сообщений

### `recordChatInsights`
Запись темы разговора (вызывается автоматически при каждом ответе).
- **Параметры:** `topic` (String — 3 слова)
- **Возвращает:** void

---

## 7. Компактные результаты (CompactToolResultConverter)

Все инструменты используют `CompactToolResultConverter`, который преобразует полные DTO в сжатое превью:

- **`resultGist`** — краткая текстовая сводка результата (например, `"size=1\n4512b4a 2026-06-03 Trialiya: feature: show compact tool result previews..."`)
- **`ToolCallResponseItem`** — интерфейс с методом `toGist()`, реализуемый всеми DTO: `GitFileContent`, `GitFileOutline`, `GitCommit`, `GitGrepMatch`, `GitFileNode`, `GitDiffEntry`, `DocumentDto`, `AttachmentDto`, `SearchAgentResult`
- **`OutlineResult`** — DTO-обёртка для `getFileOutline`, содержит `path`, `language`, `lineCount`, `parser`, `symbols` и реализует `ToolCallResponseItem`
- **`SearchAgentResult`** — результат `searchCodebase`, реализует `ToolCallResponseItem` и `ToolCallResultMetaProvider`. Gist: `"⚠ неполно • 3 шаг(ов) • краткий фрагмент отчёта…"`. ResultMeta: `complete`, `iterations`, `durationMs`, `reportChars`.

### ToolInvocation

`ToolInvocation` — отдельный record (не вложен в `ToolInvocationCollector`):

| Поле | Тип | Описание |
|---|---|---|
| `name` | String | Имя инструмента |
| `arguments` | Map | Параметры вызова |
| `status` | String | `STARTED`, `OK`, `ERROR` |
| `error` | String | Сообщение об ошибке (при ERROR) |
| `resultGist` | String | Краткая сводка результата (из `CompactToolResultConverter`) |
