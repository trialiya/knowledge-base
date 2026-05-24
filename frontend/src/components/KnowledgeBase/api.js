// ─── Knowledge Base API helpers ────────────────────────────────────────────────
// Thin wrappers around the /api/documents endpoints. Kept free of React so they
// can be unit-tested and reused outside the component tree.

const api = {
  fetchTree: () => fetch('/api/documents/tree').then((r) => r.json()),

  fetchChildren: (parentId, page = 0, size = 10) => {
    const params = new URLSearchParams({ page: String(page), size: String(size) });
    if (parentId) params.set('parentId', parentId);
    return fetch(`/api/documents/children?${params}`).then((r) => r.json());
  },

  fetchAncestors: async (id) => {
    const r = await fetch(`/api/documents/${id}/ancestors`);
    if (!r.ok) {
      const err = new Error(r.status === 404 ? 'Not found' : `Server error ${r.status}`);
      err.status = r.status;
      throw err;
    }
    return r.json();
  },

  search: (q, mode) => fetch(`/api/search?q=${encodeURIComponent(q)}&mode=${mode}`).then((r) => r.json()),

  create: (body) =>
    fetch('/api/documents', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    }),

  fetchById: async (id) => {
    const r = await fetch(`/api/documents/${id}`);
    if (!r.ok) {
      const err = new Error(r.status === 404 ? 'Not found' : `Server error ${r.status}`);
      err.status = r.status;
      throw err;
    }
    return r.json();
  },

  update: (id, patch) =>
    fetch(`/api/documents/${id}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(patch),
    }),

  delete: (id) => fetch(`/api/documents/${id}`, { method: 'DELETE' }),

  reorder: (parentId, orderedIds) =>
    fetch('/api/documents/reorder', {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ parentId, orderedIds }),
    }),
};

export default api;
