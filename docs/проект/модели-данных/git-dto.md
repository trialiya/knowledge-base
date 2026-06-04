Git-модели — только DTO (нет сущностей в БД). Используются AI-инструментами для анализа репозитория.

Все DTO реализуют интерфейс **`ToolCallResponseItem`** с методом `toGist()`, который возвращает краткую текстовую сводку для компактного отображения результатов в UI.

## DTO

### GitCommit
Запись из истории коммитов. Реализует `ToolCallResponseItem`.

| Поле | Тип | Описание |
|---|---|---|
| `hash` | String | Полный SHA |
| `shortHash` | String | Первые 8 символов SHA |
| `author` | String | Имя автора |
| `email` | String | Email автора |
| `date` | OffsetDateTime | Дата коммита (ISO-8601) |
| `message` | String | Сообщение коммита |
| `files` | List\<GitDiffEntry\> | Затронутые файлы (null если не запрошены) |

### GitDiffEntry
Одна запись из diff коммита. Реализует `ToolCallResponseItem`.

| Поле | Тип | Описание |
|---|---|---|
| `status` | String | A/M/D/R (added/modified/deleted/renamed) |
| `path` | String | Путь к файлу (новый при rename) |
| `oldPath` | String | Старый путь (только при rename) |
| `additions` | int | Добавлено строк |
| `deletions` | int | Удалено строк |
| `patch` | String | Unified diff (null если не запрошен) |

### GitFileContent
Содержимое файла с метаданными для ИИ. Реализует `ToolCallResponseItem`.

| Поле | Тип | Описание |
|---|---|---|
| `path` | String | Относительный путь |
| `content` | String | Текстовое содержимое (null для бинарных) |
| `binary` | boolean | Флаг бинарности |
| `sizeBytes` | long | Размер в байтах |
| `language` | String | Язык по расширению (java, javascript, typescript, python, sql...) |
| `lineCount` | int | Общее количество строк в файле |
| `truncated` | boolean | true если content — не весь файл |
| `fromLine` | Integer | Первая возвращённая строка (1-based), null если весь файл |
| `toLine` | Integer | Последняя возвращённая строка (1-based), null если весь файл |

### GitFileOutline
Структурный обзор файла без полного текста.

| Поле | Тип | Описание |
|---|---|---|
| `path` | String | Относительный путь |
| `language` | String | Определённый язык |
| `lineCount` | int | Всего строк |
| `parser` | String | `"tree-sitter"` или `"regex"` (фолбэк) |
| `symbols` | List\<GitSymbol\> | Символы в порядке появления |

### OutlineResult
DTO-обёртка для `getFileOutline`. Реализует `ToolCallResponseItem`.

| Поле | Тип | Описание |
|---|---|---|
| `path` | String | Относительный путь |
| `language` | String | Определённый язык |
| `lineCount` | int | Всего строк |
| `parser` | String | `"tree-sitter"` или `"regex"` |
| `symbols` | List\<GitSymbol\> | Символы в порядке появления |

### GitSymbol
Один символ (класс, метод, поле и т.д.).

| Поле | Тип | Описание |
|---|---|---|
| `kind` | String | Вид: class, interface, enum, record, method, function, field, import, table, view... |
| `name` | String | Имя символа |
| `signature` | String | Сигнатура/заголовок (может быть null) |
| `startLine` | int | Первая строка (1-based) |
| `endLine` | int | Последняя строка (1-based, включительно) |

### GitFileNode
Узел файлового дерева. Реализует `ToolCallResponseItem`.

| Поле | Тип | Описание |
|---|---|---|
| `path` | String | Полный путь от корня репо |
| `name` | String | Имя файла/каталога |
| `type` | String | `"file"` или `"directory"` |
| `size` | Long | Размер в байтах (null для каталогов) |

### GitGrepMatch
Одно совпадение grep. Реализует `ToolCallResponseItem`.

| Поле | Тип | Описание |
|---|---|---|
| `path` | String | Путь к файлу |
| `matchLine` | int | Номер строки совпадения (1-based) |
| `text` | String | Текст строки с совпадением |
