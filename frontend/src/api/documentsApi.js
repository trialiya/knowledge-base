// ─── Knowledge Base / Documents API ──────────────────────────────────────────
// Тонкие обёртки над /api/documents эндпоинтами. Единственный модуль для этого
// ресурса — раньше существовал ещё один (knowledgeBasePanel/api.js) с
// частично пересекающимся набором методов; теперь оба слились сюда.
//
// Контракт:
//   • READ-хелперы (fetch*, search*, summarize, reindex) — бросают типизированный
//     Error (err.status) при !ok и возвращают распарсенный JSON при успехе.
//   • WRITE-хелперы (create, update, delete, move, exportToFolder) — возвращают
//     сырой Response, чтобы вызывающий код мог проверить res.ok и прочитать тело
//     ошибки для показа пользователю.

import { request, requestRaw, json } from './client';

const documentsApi = {
  // ── Read ──────────────────────────────────────────────────────────────────

  fetchChildren: (parentId, page = 0, size = 10) => {
    const params = new URLSearchParams({ page: String(page), size: String(size) });
    if (parentId != null) params.set('parentId', parentId);
    return request(`/api/documents/children?${params}`);
  },

  fetchAncestors: (id) => request(`/api/documents/${id}/ancestors`),

  search: (q, mode) => request(`/api/documents/search?q=${encodeURIComponent(q)}&mode=${mode}`),

  /**
   * Поиск по имени для @mention-автодополнения. limit — макс. результатов.
   * signal — AbortSignal для отмены при следующем нажатии клавиши.
   */
  searchByName: (name, limit = 10, signal) => {
    const params = new URLSearchParams({ name, limit: String(limit) });
    return request(`/api/documents/search-by-name?${params}`, signal ? { signal } : undefined);
  },

  /** signal — опциональный AbortSignal (используется поиском по /file и /doc в композере чата). */
  fetchById: (id, signal) => request(`/api/documents/${id}`, signal ? { signal } : undefined),

  fetchHistory: (id) => request(`/api/documents/${id}/history`, { cache: 'no-store' }),

  fetchHistoryVersion: (id, version) => request(`/api/documents/${id}/history/${version}`),

  summarize: (id) => request(`/api/documents/${id}/summarize`, { method: 'POST' }),

  reindex: () => request('/api/documents/admin/reindex', { method: 'POST' }),

  // ── Write (возвращают сырой Response) ────────────────────────────────────

  create: (body) => requestRaw('/api/documents', { method: 'POST', ...json(body) }),

  update: (id, patch) => requestRaw(`/api/documents/${id}`, { method: 'PUT', ...json(patch) }),

  delete: (id) => requestRaw(`/api/documents/${id}`, { method: 'DELETE' }),

  /**
   * Переместить узел к target-родителю и вставить после afterId.
   * afterId = null → первый в уровне.
   */
  move: (id, parentId, afterId) =>
    requestRaw(`/api/documents/${id}/move`, {
      method: 'PATCH',
      ...json({ parentId: parentId ?? null, afterId: afterId ?? null }),
    }),

  /**
   * Экспорт всего дерева в серверную папку. Возвращает сырой Response
   * (бэк отвечает 204 без тела).
   */
  exportToFolder: (meta = true) => requestRaw(`/api/documents/admin/export?meta=${meta}`, { method: 'POST' }),
};

export default documentsApi;
