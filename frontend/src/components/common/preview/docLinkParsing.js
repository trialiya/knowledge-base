import { decodeFilePath } from '@/navigation/urlScheme';

/** Канонический путь документа: /knowledge/doc/123 (см. urlScheme.docPath). */
const DOC_PATH = /^\/knowledge\/doc\/(\d+)\/?$/;

/**
 * Returns the doc id ONLY for internal KB links, i.e.:
 *   /?doc=123        (root-relative, the form stored inside markdown)
 *   ?doc=123         (query-only)
 *   /kb?doc=123      (relative path + query)
 *   /knowledge/doc/123             (canonical path form — what we now render)
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

    const fromPath = url.pathname.match(DOC_PATH);
    // ids are numeric end-to-end now — parse the (always-string) URL param to a
    // Number here so downstream comparisons against the tree are number↔number.
    if (fromPath) return Number(fromPath[1]);

    const doc = url.searchParams.get('doc');
    return doc && /^\d+$/.test(doc) ? Number(doc) : null;
  } catch {
    return null;
  }
}

/**
 * Returns { project, path, fromLine, toLine } ONLY for internal file-browser links, i.e.:
 *   /files?path=backend/.../GitService.java             (the form stored inside markdown)
 *   /files/backend/.../GitService.java                  (canonical path form)
 *   /files?path=backend/.../GitService.java#L42         (single line)
 *   /files?path=backend/.../GitService.java#L42-L58     (line range)
 *   /files?path=…&project=kb                            (either form, naming a project)
 *
 * `project` is null when the link names none — the form every link written before
 * projects existed has, and it means the default project.
 *
 * Returns null for anything else (cross-origin, wrong pathname, missing path) — those
 * fall through to parseDocId / the plain external-link branch.
 */
export function parseFileLink(href) {
  if (!href) return null;
  try {
    const url = new URL(href, window.location.origin);
    if (url.origin !== window.location.origin) return null;

    let path;
    if (url.pathname === '/files') {
      path = url.searchParams.get('path');
    } else if (url.pathname.startsWith('/files/')) {
      path = decodeFilePath(url.pathname.slice('/files/'.length));
    }
    if (!path) return null;

    // Проект — в обеих формах в query. Его нет у ссылок, написанных до того, как
    // проекты появились: там он и не нужен, такая ссылка означает дефолтный.
    const project = url.searchParams.get('project') || null;

    let fromLine = null;
    let toLine = null;
    const m = url.hash.match(/^#L(\d+)(?:-L(\d+))?$/);
    if (m) {
      fromLine = Number(m[1]);
      toLine = m[2] ? Number(m[2]) : fromLine;
    }

    return { project, path, fromLine, toLine };
  } catch {
    return null;
  }
}
