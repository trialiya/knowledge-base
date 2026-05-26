# API Reference

> **Связанные документы:** [Введение](введение.md) · [Архитектура](архитектура.md) · [Модели данных](модели-данных/.content.md) · [AI-инструменты](ai-инструменты.md) · [Конфигурация](конфигурация.md) · [Руководство по установке](руководство-по-установке.md)

---

## DocumentController — `/api`

### GET `/api/documents/tree`
Полное рекурсивное дерево документов (для обратной совместимости). `description` всегда `null` — полный текст через `GET /api/documents/{id}`.

**Response:** `List<DocumentNode>`

---

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

**Response:** `List<String>` — например `["1", "7", "42"]`

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

### PATCH `/api/documents/reorder`
Изменить порядок сортировки элементов внутри папки (или на корневом уровне).

**Body:** `ReorderRequest`
```json
{
  "parentId": "42 | null",
  "orderedIds": ["7", "3", "1"]
}
```
**Response:** `204 No Content`

---

### POST `/api/documents/{id}/summarize`
Сгенерировать (или перегенерировать) AI-summary для документа. Description отправляется в LLM полностью, без усечения. `summarySourceVersion` устанавливается в текущий `descriptionVersion` — это сбрасывает флаг stale до следующего изменения description.

**Response:** `DocumentNode` (с заполненными полями `summary`, `summaryStale=false`, `summarySourceVersion`)

**Ошибки:**
- `404` — документ не найден
- `422` — документ не имеет description (нечего суммаризировать)
- `409` — конфликт конкурентного изменения

---

### GET `/api/search`
Унифицированный поиск. Ищет по `title`, `summary` и `description`.

| Параметр | Тип | По умолчанию | Описание |
|---|---|---|---|
| `q` | String | — | Поисковый запрос |
| `mode` | String | `keyword` | `keyword` / `semantic` / `hybrid` |
| `threshold` | Double? | `null` | Порог схожести (semantic/hybrid) |
| `limit` | Integer? | `null` | Макс. количество результатов |
| `kwWeight` | Double? | `null` | Вес keyword (только hybrid) |
| `semWeight` | Double? | `null` | Вес semantic (только hybrid) |

**Response:** `List<SearchResult>`

---

### POST `/api/documents/reindex`
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

## ChatController — `/api/chat`

### POST `/api/chat/stream`
Streaming-чат через Server-Sent Events.

| Параметр | Тип | Где | Описание |
|---|---|---|---|
| `conversationId` | String | Query | ID беседы |
| userMessage | String | Body | Сообщение пользователя (raw text) |

**Response:** `text/event-stream` (SSE)
- `StreamMessage` — текст ответа и/или finishReason
- `ToolCallMessage` — вызов инструмента (статус STARTED/OK)
- `ToolCallsMessage` — снапшот всех завершённых вызовов

---

### POST `/api/chat/streamTest`
Тестовый SSE-эндпоинт (имитация стриминга).

| Параметр | Тип | Где | Описание |
|---|---|---|---|
| `conversationId` | String | Query | ID беседы |
| userMessage | String | Body | Сообщение (raw text) |

**Response:** `text/event-stream`

---

### POST `/api/chat`
Обычный чат (без streaming).

| Параметр | Тип | Где | Описание |
|---|---|---|---|
| `conversationId` | String | Query | ID беседы |
| userMessage | String | Body | Сообщение пользователя (raw text) |

**Response:** `List<String>` — тексты ответов

---

### GET `/api/chat`
Список всех чатов текущего пользователя, отсортированный по `updatedAt` (сначала новые).

**Response:** `List<Chat>`

---

### GET `/api/chat/chat`
Получить чат с сообщениями.

| Параметр | Тип | Где | Описание |
|---|---|---|---|
| `conversationId` | String | Query | ID беседы |

**Response:** `Chat` (с полем `messages: List<ChatMessage>`)

**Ошибки:** `404` — не найден, `403` — чужой чат

---

### DELETE `/api/chat/chat`
Удалить чат и очистить память.

| Параметр | Тип | Где | Описание |
|---|---|---|---|
| `conversationId` | String | Query | ID беседы |

**Response:** `200 OK`

**Ошибки:** `404` — не найден, `403` — чужой чат

---

### POST `/api/chat/topic`
Создать или обновить тему чата.

| Параметр | Тип | Где | Описание |
|---|---|---|---|
| `conversationId` | String | Query | ID беседы |
| topic | String | Body | Текст темы (raw text) |

**Response:** `200 OK`

**Ошибки:** `403` — чужой чат

---

### POST `/api/chat/jira`
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

### POST `/api/chat/jira/{conversationId}/refresh`
Обновить JIRA-чат — перезагрузить данные issue и заменить вложения.

**Body:** JIRA URL (raw text)

**Response:** `Chat`

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

### POST `/api/chat/{conversationId}/attachments`
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

### GET `/api/chat/{conversationId}/attachments`
Список вложений чата.

**Response:** `List<Attachment>`

---

### GET `/api/chat/{conversationId}/attachments/count`
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
