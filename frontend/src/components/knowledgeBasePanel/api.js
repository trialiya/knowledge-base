// ─── Knowledge Base API helpers ────────────────────────────────────────────────
// Thin wrappers around the /api/documents endpoints. Kept free of React so they
// can be unit-tested and reused outside the component tree.
//
// Contract (made consistent during the refactor):
//   • READ helpers (fetchTree, fetchChildren, search, searchByName, fetchById,
//     fetchAncestors, fetchHistory, fetchHistoryVersion, summarize) check
//     response.ok, throw a typed Error (err.status set) on failure, and resolve
//     to the parsed JSON body on success.
//   • WRITE helpers (create, update, delete, moveToParent, reorder) resolve to
//     the raw Response so callers can branch on res.ok and read an error body.

/** Throws a typed Error for a non-OK response; otherwise returns it. */
async function ensureOk(r, label) {
  if (!r.ok) {
    const err = new Error(r.status === 404 ? 'Not found' : `${label} failed: ${r.status}`);
    err.status = r.status;
    throw err;
  }
  return r;
}

const json = (r, label) => ensureOk(r, label).then((res) => res.json());

const api = {
  fetchChildren: (parentId, page = 0, size = 10) => {
    const params = new URLSearchParams({ page: String(page), size: String(size) });
    if (parentId != null) params.set('parentId', parentId);
    return fetch(`/api/documents/children?${params}`).then((r) => json(r, 'Children'));
  },

  fetchAncestors: (id) => fetch(`/api/documents/${id}/ancestors`).then((r) => json(r, 'Ancestors')),

  search: (q, mode) =>
    fetch(`/api/documents/search?q=${encodeURIComponent(q)}&mode=${mode}`).then((r) => json(r, 'Search')),

  /**
   * Find documents by name fragment — used by @mention autocomplete.
   * Returns up to `limit` DocumentNode objects, exact matches first.
   * Pass an AbortSignal to cancel an in-flight request (keystroke debounce).
   */
  searchByName: (name, limit = 10, signal) => {
    const params = new URLSearchParams({ name, limit: String(limit) });
    return fetch(`/api/documents/search-by-name?${params}`, signal ? { signal } : undefined).then((r) =>
      json(r, 'SearchByName'),
    );
  },

  fetchById: (id) => fetch(`/api/documents/${id}`).then((r) => json(r, 'Document')),

  // Короткий список версий (без тяжёлого description) — newest-first.
  fetchHistory: (id) => fetch(`/api/documents/${id}/history`, { cache: 'no-store' }).then((r) => json(r, 'History')),

  // Полная запись одной версии (с description) — подтягивается по требованию.
  fetchHistoryVersion: (id, version) =>
    fetch(`/api/documents/${id}/history/${version}`).then((r) => json(r, 'History version')),

  create: (body) =>
    fetch('/api/documents', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    }),

  update: (id, patch) =>
    fetch(`/api/documents/${id}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(patch),
    }),

  delete: (id) => fetch(`/api/documents/${id}`, { method: 'DELETE' }),

  moveToParent: (id, newParentId) =>
    fetch(`/api/documents/${id}/parent`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ parentId: newParentId ?? null }),
    }),

  reorder: (parentId, orderedIds) =>
    fetch('/api/documents/reorder', {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ parentId, orderedIds }),
    }),

  summarize: (id) => fetch(`/api/documents/${id}/summarize`, { method: 'POST' }).then((r) => json(r, 'Summarize')),
};

export default api;
