// ─── Tree traversal ───────────────────────────────────────────────────────────

export function findNodeById(nodes, id) {
  for (const n of nodes) {
    if (n.id === id) return n;
    if (n.children) {
      const found = findNodeById(n.children, id);
      if (found) return found;
    }
  }
  return null;
}

/** Returns array of ancestor nodes from root up to (not including) targetId */
export function findPath(nodes, targetId, path = []) {
  for (const n of nodes) {
    if (n.id === targetId) return path;
    if (n.children) {
      const result = findPath(n.children, targetId, [...path, n]);
      if (result) return result;
    }
  }
  return null;
}

export function flatFolders(nodes, acc = []) {
  nodes.forEach((n) => {
    if (n.type === 'folder') {
      acc.push(n);
      if (n.children) flatFolders(n.children, acc);
    }
  });
  return acc;
}

// ─── URL state ────────────────────────────────────────────────────────────────

export function getUrlState() {
  const params = new URLSearchParams(window.location.search);
  return {
    docId: params.get('doc') || null,
    tab: params.get('tab') || 'summary',
    searchQuery: params.get('search') || '',
    searchMode: params.get('mode') || 'hybrid',
  };
}

export function setUrlState(docId, tab, searchQuery, searchMode) {
  const params = new URLSearchParams();
  if (docId) params.set('doc', docId);
  if (tab && tab !== 'summary') params.set('tab', tab);
  if (searchQuery && !docId) {
    params.set('search', searchQuery);
    if (searchMode) params.set('mode', searchMode);
  }
  const search = params.toString();
  const url = search ? `?${search}` : window.location.pathname;
  window.history.pushState({}, '', url);
}

// ─── Content helpers ──────────────────────────────────────────────────────────

/** Strip markdown syntax and return first non-empty line, capped at maxLen chars */
export function makeSnippet(description, maxLen = 200) {
  if (!description) return null;
  const clean = description
    .replace(/^#{1,6}\s+/gm, '')
    .replace(/[*_`~>]/g, '')
    .trim();
  const first = clean.split('\n').find((l) => l.trim().length > 0) || '';
  return first.length > maxLen ? first.slice(0, maxLen) + '…' : first || null;
}
