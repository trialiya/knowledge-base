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

  // ── Выгрузка/загрузка файлов ─────────────────────────────────────────────

  /**
   * Ссылка на скачивание узла: документ отдаётся одним .md, папка — zip-архивом
   * поддерева. Именно ссылка, а не fetch: браузер сам стримит ответ в файл, и
   * содержимое не проходит через память вкладки.
   */
  downloadUrl: (id, meta = false) => `/api/documents/${id}/download?meta=${meta}`,

  // ── Потоковые операции администрирования ─────────────────────────────────
  // Возвращают сырой Response с телом-потоком SSE; читает его useJobStream.
  // Ошибку до начала потока (не задан DOCUMENTS_EXPORT_PATH и т.п.) видно по
  // res.ok, дальше — по терминальному кадру error.

  /** Экспорт в серверную папку с прогрессом по узлам. */
  exportStream: (meta = true, signal) =>
    requestRaw(`/api/documents/admin/export/stream?meta=${meta}`, {
      method: 'POST',
      headers: { Accept: 'text/event-stream' },
      signal,
    }),

  /** Сравнение серверной папки экспорта с базой. Ничего не пишет. */
  importDiff: (parentId, signal) => {
    const params = new URLSearchParams();
    if (parentId != null) params.set('parentId', parentId);
    const query = params.toString();
    return requestRaw(`/api/documents/admin/import/diff${query ? `?${query}` : ''}`, {
      headers: { Accept: 'text/event-stream' },
      signal,
    });
  },

  /**
   * Импорт выбранных записей сравнения.
   * paths — пути из diff; пустой список означает «всё, что меняется».
   */
  importApply: ({ parentId = null, paths = null, deleteMissing = false }, signal) =>
    requestRaw('/api/documents/admin/import', {
      method: 'POST',
      headers: { Accept: 'text/event-stream' },
      signal,
      ...json({ parentId, paths, deleteMissing }),
    }),
};

export default documentsApi;
