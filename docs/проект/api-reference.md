# API Reference

> **Связанные документы:** [Введение](введение.md) · [Архитектура](архитектура.md) · [Модели данных](модели-данных/.content.md) · [AI-инструменты](ai-инструменты.md) · [Конфигурация](конфигурация.md) · [Руководство по установке](руководство-по-установке.md)

---

## DocumentController — `/api/documents`

### GET `/api/documents/children`
Lazy-load дочерних узлов с пагинацией. `description` — сниппет ≤150 символов или `null`.

| Параметр | Тип | По умолчанию | Описание |
|---|---|---|---|
| `parentId` | Long? | `null` | ID родителя (`null` — корень) |
| `page` | int | `0` | Номер страницы |
| `size` | int | `10` | Размер страницы |

**Response:** `PagedChildren` (totalElements, totalPages, hasNext, content)

---

### GET `/api/documents/{id}`
Получить один документ по ID с полным `description`. Используется фронтендом при клике на любой узел (tree, children, search results) — все они возвращают сниппет или `null`, а полный текст загружается этим эндпоинтом.

**Response:** `DocumentNode`

**Ошибки:** `404` — документ не найден

---

### GET `/api/documents/{id}/ancestors`
ID предков от корня до узла (не включая сам узел). Используется UI для раскрытия ветки при прямом переходе.

**Response:** `List<Long>` — например `[1, 7, 42]`

---

### GET `/api/documents/{id}/history`
История изменений description документа. Возвращает список снепшотов **без поля `description`** — только метаданные (version, title, type, descriptionVersion, updatedAt). Полный текст конкретной версии загружается отдельно через `GET /api/documents/{id}/history/{version}`.

**Response:** `List<DocumentHistoryShort>`

**Ошибки:** `404` — документ не найден

---

### GET `/api/documents/{id}/history/{version}`
Получить **полную** версию истории (с `description`) по номеру версии. Используется в `HistoryModal` для ленивой подгрузки описания выбранной версии.

**Response:** `DocumentHistory`

**Ошибки:** `404` — документ или версия не найдены

---

### GET `/api/documents/search-by-name`
Поиск документов/папок по фрагменту названия (регистронезависимо). Используется для автокомплита @mention в Markdown-редакторе. Точные совпадения возвращаются первыми, затем частичные — по длине названия.

| Параметр | Тип | По умолчанию | Описание |
|---|---|---|---|
| `name` | String | — | Фрагмент названия (мин. 1 символ) |
| `limit` | int | `10` | Макс. результатов (макс. 20) |

**Response:** `List<DocumentNode>`

---

### POST `/api/documents`
Создать документ или папку.

**Body:** `CreateDocumentRequest`
```json
{
  "title": "string",
  "type": "document | folder",
  "parentId": "long | null",
  "description": "string"
}
```
**Response:** `201 Created` → `Document`

---

### PUT `/api/documents/{id}`
Обновить документ. Сохраняет снапшот в `document_history` перед обновлением. Использует оптимистичную блокировку. При изменении `description` инкрементирует `descriptionVersion` — это делает существующий summary устаревшим (stale), но не удаляет его.

**Body:** `UpdateDocumentRequest`
```json
{
  "title": "string",
  "description": "string"
}
```
**Response:** `Document`

**Ошибки:**
- `403` — попытка переименовать системный документ
- `404` — документ не найден
- `409` — конфликт конкурентного изменения (документ был изменён другим запросом)

---

### DELETE `/api/documents/{id}`
Удалить документ. История удаляется автоматически (`ON DELETE CASCADE`).

**Response:** `204 No Content`

**Ошибки:** `403` — системный документ

---

### PATCH `/api/documents/{id}/move`
Переместить документ/папку в целевого родителя и на конкретную позицию за один атомарный вызов. Заменяет старую пару `moveToParent` + `reorder`: клиент указывает одного соседа вместо всего списка siblings, поэтому частично загруженное дерево на фронтенде не может испортить порядок — точный слот вычисляется на сервере из актуального состояния БД.

**Body:** `MoveRequest`
```json
{ "parentId": 1, "afterId": 7 }     // в папку 1, сразу после узла 7
{ "parentId": 1, "afterId": null }  // в папку 1, первым
{ "parentId": null, "afterId": 42 } // в корень, сразу после 42
```

**Response:** `Document`

**Ошибки:**
- `400` — цикл, или `afterId == id`
- `403` — системный узел
- `404` — узел, целевой родитель или `afterId` не найдены
- `409` — конкурентное изменение
- `422` — цель не папка, или `afterId` принадлежит другому уровню

---

### POST `/api/documents/{id}/summarize`
Сгенерировать (или перегенерировать) AI-summary для документа. Description отправляется в LLM полностью, без усечения. `summarySourceVersion` устанавливается в текущий `descriptionVersion` — это сбрасывает флаг stale до следующего изменения description.

**Response:** `DocumentNode` (с заполненными полями `summary`, `summaryStale=false`, `summarySourceVersion`)

**Ошибки:**
- `404` — документ не найден
- `422` — документ не имеет description (нечего суммаризировать)
- `409` — конфликт конкурентного изменения

---

### GET `/api/documents/search`
Унифицированный поиск. Ищет по `title`, `summary` и `description`. Результаты включают `parentList` — хлебные крошки (предки от корня до родителя).

| Параметр | Тип | По умолчанию | Описание |
|---|---|---|---|
| `q` | String | — | Поисковый запрос |
| `mode` | String | `keyword` | `keyword` / `semantic` / `hybrid` |
| `threshold` | Double? | `null` | Порог схожести (semantic/hybrid) |
| `limit` | Integer? | `null` | Макс. количество результатов |
| `kwWeight` | Double? | `null` | Вес keyword (только hybrid) |
| `semWeight` | Double? | `null` | Вес semantic (только hybrid) |

**Response:** `List<SearchResult>` — каждый результат содержит `parentList: List<Parent>` с полями `id` и `title`

---

### POST `/api/documents/admin/reindex`
Полный переиндекс всех документов (генерация эмбеддингов заново).

**Response:** `ReindexResponse`
```json
{ "indexed": 42 }
```

---

### POST `/api/documents/admin/export`
Экспорт всех документов. Внутренние ссылки `(/?doc=ID)` перезаписываются в относительные пути — экспортированный Markdown становится самодостаточным.

| Параметр | Тип | По умолчанию | Описание |
|---|---|---|---|
| `meta` | boolean | `true` | `true` — экспорт с `.yaml`-файлами метаданных; `false` — только `.md` |

**Response:** `204 No Content`

---

## ChatController — `/api/chats`

### GET `/api/chats`
Список всех чатов текущего пользователя (только метаданные, без сообщений), отсортированный по `updatedAt` (сначала новые).

**Response:** `List<Chat>` — каждый с полем `model` (выбранная модель или `null` для дефолтной)

---

### GET `/api/chats/{conversationId}`
Получить чат с сообщениями.

| Параметр | Тип | По умолчанию | Описание |
|---|---|---|---|
| `includeMessages` | boolean | `true` | `false` — только метаданные без сообщений |

**Response:** `Chat` (с полем `messages: List<ChatMessage>` при `includeMessages=true`, `null` при `false`)

**Ошибки:** `404` — не найден, `403` — чужой чат

---

### GET `/api/chats/{conversationId}/messages`
Пагинированная загрузка сообщений чата (без SYSTEM-сообщений и суммаризаций).

| Параметр | Тип | По умолчанию | Описание |
|---|---|---|---|
| `limit` | int | `20` | Размер страницы (1–100) |
| `beforeCreatedAt` | ISO datetime | — | Курсор: createdAt последнего загруженного сообщения |
| `beforeId` | long | — | Курсор: id последнего загруженного сообщения |

Без курсора — возвращает последние `limit` сообщений. С курсором — сообщения до указанной позиции.

**Response:** `MessagePage`
```json
{
  "messages": [ ... ],
  "hasMore": true,
  "oldestCursor": { "createdAt": "2026-06-09T10:00:00", "id": 42 }
}
```

---

### DELETE `/api/chats/{conversationId}`
Удалить чат и очистить память.

**Response:** `200 OK`

**Ошибки:** `404` — не найден, `403` — чужой чат

---

### PUT `/api/chats/{conversationId}/topic`
Создать или обновить тему чата (идемпотентный).

**Body:** `String` — текст темы (raw text)

**Response:** `200 OK`

**Ошибки:** `403` — чужой чат

---

### POST `/api/chats/{conversationId}/messages`
Обычный чат (без streaming). Возвращает ответ ассистента как JSON.

| Параметр | Тип | По умолчанию | Описание |
|---|---|---|---|
| `model` | String | — | ID модели (опционально, переопределяет модель чата) |

**Body:** `String` — сообщение пользователя (raw text)

**Response:** `List<String>` — тексты ответов

---

### POST `/api/chats/{conversationId}/runs`
Запустить фоновую генерацию ответа. Возвращает `runId` сразу, сам ответ приходит через SSE event channel (`GET /events`).

| Параметр | Тип | По умолчанию | Описание |
|---|---|---|---|
| `model` | String | — | ID модели (опционально) |
| `clientMsgId` | String | — | ID клиента-инициатора (защита от задвоения пузыря) |

**Body:** `String` — сообщение пользователя (raw text)

**Response:** `200 OK`
```json
{ "runId": "uuid" }
```

**Ошибки:** `409 Conflict` — генерация уже идёт в этом чате

---

### GET `/api/chats/{conversationId}/events`
SSE-поток событий чата: стриминг ответа + кросс-вкладочная синхронизация.

| Параметр | Тип | По умолчанию | Описание |
|---|---|---|---|
| `fromSeq` | long | `0` | Дозагрузить события с seq > fromSeq |

**Response:** `text/event-stream` (SSE)
- `ChatEvent` — каждое событие: `seq`, `type`, `runId`, `clientMsgId`, `payload`
- Типы: `USER_MESSAGE`, `RUN_STARTED`, `STREAM`, `TOOL_CALL`, `TOOL_CALLS`, `RUN_DONE`, `RUN_STOPPED`, `RUN_ERROR`

---

### POST `/api/chats/{conversationId}/runs/{runId}/stop`
Остановить активный прогон. Идемпотентно.

**Response:** `200 OK`

---

### GET `/api/chats/{conversationId}/runs/active`
runId активного прогона чата (или пустой объект).

**Response:**
```json
{ "runId": "uuid" }   // если прогон активен
{}                     // если нет активного прогона
```

---

### POST `/api/chats/jira`
Создать JIRA-чат с предзагруженным контекстом.

**Body:** `CreateJiraChatRequest`
```json
{
  "jiraUrl": "https://instance.atlassian.net/browse/PROJ-123",
  "confluenceUrl": "https://instance.atlassian.net/wiki/spaces/.../pages/12345",
  "title": "Optional custom title"
}
```
**Response:** `Chat` (с пустым списком сообщений)

---

### POST `/api/chats/{conversationId}/refresh`
Обновить JIRA-чат — перезагрузить данные issue и заменить вложения.

**Body:** JIRA URL (raw text)

**Response:** `Chat`

---

### GET `/api/chats/models`
Список доступных AI-моделей и модель по умолчанию.

**Response:** `ChatModelProperties`
```json
{
  "defaultModel": { "id": "gpt-4o", "label": "GPT-4o" },
  "models": [
    { "id": "gpt-4o", "label": "GPT-4o" },
    { "id": "gpt-4o-mini", "label": "GPT-4o Mini" }
  ]
}
```

---

### PUT `/api/chats/{conversationId}/model`
Выбрать AI-модель для чата. Пустое тело — сброс к дефолтной модели.

**Body:** `String` — id модели (raw text) или пусто

**Response:** `200 OK`

**Ошибки:** `403` — чужой чат, `400` — неизвестная модель

---

## SettingsController — `/api/settings`

### GET `/api/settings/ai-config`
Полный снапшот AI-конфигурации для панели «Настройки → Модели».

**Response:** `AiConfigResponse`
```json
{
  "chat": {
    "defaultModel": { "id": "gpt-4o", "label": "GPT-4o" },
    "models": [ ... ],
    "options": { "maxTokens": 30000, "temperature": 0.1, "topP": 0.8 }
  },
  "embedding": {
    "model": "bge-m3",
    "reindexBatchSize": 50,
    "chunker": { "maxTokens": 512, "overlapTokens": 64 },
    "cache": { "enabled": true, "ttlDays": 30 }
  },
  "searchCodebase": {
    "enabled": true,
    "modelId": "gpt-4o-mini",
    "maxTokens": 12000,
    "maxIterations": 30
  },
  "summarize": {
    "tokenThreshold": 3000,
    "messageCountThreshold": 20,
    "overlapMessages": 10
  }
}
```

---

## GitController — `/api/git`

Read-only эндпоинты для автодополнения `/file` в композере чата. Делегируют в `GitService`, который обеспечивает доступ только к tracked-файлам, защиту от path traversal и ограничения по бинарным/большим файлам.

### GET `/api/git/files/search`
Fuzzy-поиск tracked-файлов по имени (subsequence match, регистронезависимо). Результаты ранжированы: лучшее совпадение первым.

| Параметр | Тип | По умолчанию | Описание |
|---|---|---|---|
| `q` | String | — | Часть имени файла, например `mgi` → `MessageInput` |
| `limit` | int | `10` | Макс. результатов |

**Response:** `List<GitFileNode>` — `{ path, name, type: "file", size }`

---

### GET `/api/git/files/content`
Содержимое файла для превью/разворачивания чипа. Диапазон строк опционален.

| Параметр | Тип | По умолчанию | Описание |
|---|---|---|---|
| `path` | String | — | Путь к файлу относительно корня репо |
| `from` | int? | `null` | Первая строка (1-based, включительно) |
| `to` | int? | `null` | Последняя строка (1-based, включительно) |

**Response:** `GitFileContent` — `{ path, content, binary, sizeBytes, language, totalLines, ... }`

**Ошибки:** `400` — путь пустой или содержит `..`, `/`, `-` в начале, `\0`

---

## PhraseController — `/api/phrases` и `/api/admin/phrases`

### Публичные (чат)

| Метод | Путь | Назначение |
|---|---|---|
| `GET` | `/api/phrases` | Только `enabled`, `ORDER BY category, position` |
| `PATCH` | `/api/phrases/{id}/favorite?value=true\|false` | Переключить избранное |

### Админские (настройки)

| Метод | Путь | Назначение |
|---|---|---|
| `GET` | `/api/admin/phrases?q=` | Все фразы (вкл. выключенные); `q` — быстрый поиск по `label` (ILIKE) |
| `POST` | `/api/admin/phrases` | Создать (добавляется в конец своей категории) |
| `PUT` | `/api/admin/phrases/{id}` | Изменить (смена категории → переезд в конец новой) |
| `DELETE` | `/api/admin/phrases/{id}` | Удалить |
| `PATCH` | `/api/admin/phrases/{id}/favorite?value=` | То же, что публичный тоггл |
| `PATCH` | `/api/admin/phrases/{id}/enabled?value=` | Переключить только флаг `enabled` |
| `PATCH` | `/api/admin/phrases/{id}/move` | Переупорядочить; тело `{ "position": <слот соседа> }` |

**Ошибки:**
- `404` — неизвестный `id` (NoSuchElementException)
- `400` — пустое `category`/`label`/`text` после трима (IllegalArgumentException)
- `DELETE` → `204 No Content`

---

## AttachmentController — `/api`

### POST `/api/documents/{documentId}/attachments`
Загрузить вложение в документ.

**Content-Type:** `multipart/form-data`

| Параметр | Тип | Где | Описание |
|---|---|---|---|
| `documentId` | Long | Path | ID документа |
| `file` | MultipartFile | Form | Файл (поле `file`) |

**Response:** `201 Created` → `Attachment`

---

### POST `/api/chats/{conversationId}/attachments`
Загрузить вложение в чат.

**Content-Type:** `multipart/form-data`

| Параметр | Тип | Где | Описание |
|---|---|---|---|
| `conversationId` | String | Path | ID беседы |
| `file` | MultipartFile | Form | Файл (поле `file`) |

**Response:** `201 Created` → `Attachment`

---

### GET `/api/documents/{documentId}/attachments`
Список вложений документа.

**Response:** `List<Attachment>`

---

### GET `/api/chats/{conversationId}/attachments`
Список вложений чата.

**Response:** `List<Attachment>`

---

### GET `/api/chats/{conversationId}/attachments/count`
Количество вложений чата.

**Response:** `long`

---

### GET `/api/attachments/{id}`
Метаданные вложения.

**Response:** `Attachment`

---

### GET `/api/attachments/{id}/content`
Текстовое содержимое вложения.

**Response:** `text/plain` — `String`

---

### DELETE `/api/attachments/{id}`
Удалить вложение.

**Response:** `204 No Content`

---

### POST `/api/attachments/{id}/summarize`
AI-суммаризация вложения (генерирует и сохраняет summary).

**Response:** `Attachment` (с заполненным полем summary)

---

### GET `/api/attachments/search`
Поиск по вложениям.

| Параметр | Тип | Где | Описание |
|---|---|---|---|
| `q` | String | Query | Поисковый запрос |

**Response:** `List<Attachment>`
