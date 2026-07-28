## Сущности

### EmbeddingTaskEntity
Таблица: `embedding_tasks`. Outbox-таблица для управления асинхронными задачами эмбеддинга документов и вложений.

| Поле | Тип | Описание |
|---|---|---|
| `id` | Long | PK |
| `entityType` | EmbeddingEntityType | `DOCUMENT`, `ATTACHMENT` — тип сущности для эмбеддинга |
| `entityId` | Long | FK к документу или вложению |
| `status` | EmbeddingTaskStatus | Статус задачи (см. стейт-машину ниже) |
| `attempts` | int | Количество попыток обработки |
| `claimToken` | UUID | Token для предотвращения race condition между воркерами (заполняется при захвате задачи) |
| `createdAt` | OffsetDateTime | Время создания задачи |
| `updatedAt` | OffsetDateTime | Время последнего обновления |

**Стейт-машина:** `pending → starting → done / failed / pending (retry) / superseded`
- **pending** → задача в очереди
- **starting** → воркер захватил задачу (заполнен claimToken)
- **done** → эмбеддинг успешно сгенерирован
- **failed** → ошибка (attempts > лимит) → задача отклоняется
- **pending (retry)** → ошибка с автоматическим повтором (exponential backoff)
- **superseded** → новая pending-задача для того же entity зачислена в очередь, пока старая была в flight → новая пересчитает эмбеддинг, старую можно игнорировать

**Защита от race condition:** `claimToken` (random UUID) валидируется при markDone/markFailed, так что stale worker не может перезаписать результаты, захваченные другим воркером или stuck-task reaper.

### EmbeddingCacheEntity
Таблица: `embedding_cache`. Кэш эмбеддингов для снижения затрат на вызовы embedding API.

| Поле | Тип | Описание |
|---|---|---|
| `id` | Long | PK |
| `textHash` | String | Нижний регистр SHA-256 hex digest исходного текста (64 символа) |
| `model` | String | Модель, например `"text-embedding-3-small"` |
| `embedding` | float[] | Закэшированный вектор (pgvector) |
| `createdAt` | OffsetDateTime | Время создания |
| `lastUsedAt` | OffsetDateTime | Обновляется при каждом cache hit для LRU-вытеснения |

**Ключ поиска:** `(textHash, model)` — один и тот же текст с разными моделями хранится отдельно.

**LRU-вытеснение:** `EmbeddingCacheCleanupTask` использует `lastUsedAt` для удаления старых записей.

---

## Поток эмбеддинга

1. **Триггер:** при сохранении/обновлении документа или вложения → `EmbeddingEnqueuer` создаёт `EmbeddingTaskEntity` с `status=pending`
2. **Захват:** `EmbeddingWorker.claimPending()` получает batch задач, заполняет `claimToken`
3. **Обработка:** вызов embedding API (проверка cache через `textHash + model`)
4. **Результат:** `markDone()` с валидацией `claimToken` → запись в `document_embeddings` или `attachment_embeddings`
5. **Supersede:** если поступила новая pending-задача за время обработки → `EmbeddingTaskEntity.superseded`, новая перезапустит цикл

---

## Связи

- `embedding_tasks.entityId` → `documents.id` (если entityType = DOCUMENT)
- `embedding_tasks.entityId` → `attachments.id` (если entityType = ATTACHMENT)
- `document_embeddings.documentId` → `documents.id` (UNIQUE)
- `attachment_embeddings.attachmentId` → `attachments.id` (UNIQUE)
- `embedding_cache` — независимо, хранит (textHash, model) → vector
