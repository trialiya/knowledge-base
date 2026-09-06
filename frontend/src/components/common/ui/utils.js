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

// ─── Path helpers ───────────────────────────────────────────────────────────

/** Short file name from a repo-relative path (last path segment). */
export function baseName(path) {
  if (!path) return '';
  const i = path.lastIndexOf('/');
  return i >= 0 ? path.slice(i + 1) : path;
}

/**
 * Directory part of a repo-relative path, without the trailing slash; `''` for
 * a file at the repository root.
 *
 * Считать его вычитанием длины имени из длины пути нельзя: у файла в корне
 * вычитается ещё и разделитель, которого там нет, и `README.md` показывается
 * в каталоге `README.m`. Позиция слэша знает про этот случай сама.
 */
export function dirName(path) {
  if (!path) return '';
  const i = path.lastIndexOf('/');
  return i > 0 ? path.slice(0, i) : '';
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
