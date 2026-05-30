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
 * Записывает URL в историю, но ТОЛЬКО если query реально изменился.
 * Это устраняет дублирующие записи истории (например, когда App.js уже
 * сделал pushState на переход, а компонент в ответ на навигацию пишет
 * тот же самый URL повторно) — из-за них кнопка «Назад» работала через раз.
 *
 * @param {URLSearchParams} params
 * @param {{ replace?: boolean }} [opts] — replace:true делает replaceState вместо pushState
 */
function commitUrl(params, { replace = false } = {}) {
  const next = `?${params.toString()}`;
  // location.search пустой ('') соответствует '?'
  const current = window.location.search || '?';
  if (next === current) return;
  if (replace) {
    window.history.replaceState({}, '', next);
  } else {
    window.history.pushState({}, '', next);
  }
}

/**
 * Switch the active top-level tab without clobbering other params.
 * opts.clearKb — убрать KB-параметры (doc/tab/search/mode), напр. при переходе в чат.
 */
export function setUrlTab(view, opts = {}) {
  const { clearKb = false, ...commitOpts } = opts;
  const params = new URLSearchParams(window.location.search);
  params.set('view', view);
  if (clearKb) {
    params.delete('doc');
    params.delete('tab');
    params.delete('search');
    params.delete('mode');
  }
  commitUrl(params, commitOpts);
}

/**
 * Update KB-specific URL params (doc, tab, search, mode).
 * Preserves `view` and `chat` params.
 */
export function setKBUrlState(docId, docTab, searchQuery, searchMode, opts) {
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

  commitUrl(params, opts);
}

/**
 * Update chat-specific URL param.
 * Keeps `view=chat` and `chat`; strips KB-only params (doc/tab/search/mode)
 * so the chat URL stays clean and chat/KB history entries don't blend
 * together (a stale `doc` in a chat URL broke the browser Back button).
 */
export function setChatUrlState(chatId, opts) {
  const params = new URLSearchParams(window.location.search);

  // Always keep view=chat when chat is updating its URL
  params.set('view', 'chat');

  if (chatId) {
    params.set('chat', chatId);
  } else {
    params.delete('chat');
  }

  // В чате KB-параметрам не место — убираем, чтобы история не смешивалась.
  // Последний открытый документ App.js хранит в памяти (lastDocId) и
  // восстанавливает при клике на вкладку «База знаний».
  params.delete('doc');
  params.delete('tab');
  params.delete('search');
  params.delete('mode');

  commitUrl(params, opts);
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
