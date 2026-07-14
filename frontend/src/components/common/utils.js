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
