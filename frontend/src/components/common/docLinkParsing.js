/**
 * Returns the doc id ONLY for internal KB links, i.e.:
 *   /?doc=123        (root-relative)
 *   ?doc=123         (query-only)
 *   /kb?doc=123      (relative path + query)
 *   https://<this-site>/?doc=123   (absolute, but same origin)
 *
 * Returns null for any external URL (different origin) even if it happens
 * to carry a ?doc=N param — those render as a normal external <a>.
 */
export function parseDocId(href) {
  if (!href) return null;
  try {
    // Resolve against the current page so relative links work; absolute
    // external URLs keep their own origin.
    const url = new URL(href, window.location.origin);

    // Reject cross-origin links — they are external sites, not KB docs.
    if (url.origin !== window.location.origin) return null;

    const doc = url.searchParams.get('doc');
    // ids are numeric end-to-end now — parse the (always-string) URL param to a
    // Number here so downstream comparisons against the tree are number↔number.
    return doc && /^\d+$/.test(doc) ? Number(doc) : null;
  } catch {
    return null;
  }
}

/**
 * Returns { path, fromLine, toLine } ONLY for internal file-browser links, i.e.:
 *   /files?path=backend/.../GitService.java
 *   /files?path=backend/.../GitService.java#L42        (single line)
 *   /files?path=backend/.../GitService.java#L42-L58     (line range)
 *
 * Returns null for anything else (cross-origin, wrong pathname, missing path) — those
 * fall through to parseDocId / the plain external-link branch.
 */
export function parseFileLink(href) {
  if (!href) return null;
  try {
    const url = new URL(href, window.location.origin);
    if (url.origin !== window.location.origin) return null;
    if (url.pathname !== '/files') return null;

    const path = url.searchParams.get('path');
    if (!path) return null;

    let fromLine = null;
    let toLine = null;
    const m = url.hash.match(/^#L(\d+)(?:-L(\d+))?$/);
    if (m) {
      fromLine = Number(m[1]);
      toLine = m[2] ? Number(m[2]) : fromLine;
    }

    return { path, fromLine, toLine };
  } catch {
    return null;
  }
}
