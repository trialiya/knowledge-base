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
//
// Unified URL scheme: ?view=chat|knowledge&chat=<id>&doc=<id>&tab=<tab>&search=<q>&mode=<m>
//
// `view` determines which tab is active.
// `chat` and `doc` params coexist peacefully — each component reads only its own.

export function getUrlState() {
  const params = new URLSearchParams(window.location.search);
  return {
    tab: params.get('view') || null, // active top-level tab
    chatId: params.get('chat') || null,
    docId: params.get('doc') || null,
    docTab: params.get('tab') || 'summary', // KB detail tab
    searchQuery: params.get('search') || '',
    searchMode: params.get('mode') || 'hybrid',
  };
}

/**
 * Switch the active top-level tab without clobbering other params.
 */
export function setUrlTab(view) {
  const params = new URLSearchParams(window.location.search);
  params.set('view', view);
  window.history.pushState({}, '', `?${params.toString()}`);
}

/**
 * Update KB-specific URL params (doc, tab, search, mode).
 * Preserves `view` and `chat` params.
 */
export function setKBUrlState(docId, docTab, searchQuery, searchMode) {
  const params = new URLSearchParams(window.location.search);

  // Always keep view=knowledge when KB is updating its URL
  params.set('view', 'knowledge');

  // Set or remove KB params
  if (docId) {
    params.set('doc', docId);
    params.delete('search');
    params.delete('mode');
  } else {
    params.delete('doc');
  }

  if (docTab && docTab !== 'summary') {
    params.set('tab', docTab);
  } else {
    params.delete('tab');
  }

  if (searchQuery && !docId) {
    params.set('search', searchQuery);
    if (searchMode) params.set('mode', searchMode);
  } else if (!searchQuery) {
    params.delete('search');
    params.delete('mode');
  }

  const url = `?${params.toString()}`;
  window.history.pushState({}, '', url);
}

/**
 * Update chat-specific URL param.
 * Preserves `view` and KB params.
 */
export function setChatUrlState(chatId) {
  const params = new URLSearchParams(window.location.search);

  // Always keep view=chat when chat is updating its URL
  params.set('view', 'chat');

  if (chatId) {
    params.set('chat', chatId);
  } else {
    params.delete('chat');
  }

  const url = `?${params.toString()}`;
  window.history.pushState({}, '', url);
}

// ── Legacy alias (used internally by KnowledgeBase before refactor) ───────────
export const setUrlState = setKBUrlState;

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
