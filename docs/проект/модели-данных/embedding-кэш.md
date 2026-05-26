## EmbeddingCacheEntity
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
