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
 * Returns { project, path, rev, fromLine, toLine } ONLY for internal file-browser links, i.e.:
 *   /files?path=backend/.../GitService.java             (the form stored inside markdown)
 *   /files/backend/.../GitService.java                  (canonical path form)
 *   /files?path=backend/.../GitService.java#L42         (single line)
 *   /files?path=backend/.../GitService.java#L42-L58     (line range)
 *   /files?path=…&project=kb                            (either form, naming a project)
 *   /files?path=…&rev=<hash>                            (the file as of that commit)
 *
 * `project` is null when the link names none — the form every link written before
 * projects existed has, and it means the default project. `rev` is null for the
 * working tree: a link carries it when the model quoted the file as of a commit,
 * and the preview must show that version, not today's.
 *
 * Returns null for anything else (cross-origin, wrong pathname, missing path) — those
 * fall through to parseDocId / the plain external-link branch.
 */
export function parseFileLink(href) {
  if (!href) return null;
  try {
    const url = new URL(href, window.location.origin);
    if (url.origin !== window.location.origin) return null;

    const path = filesLinkPath(url);
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

    const rev = url.searchParams.get('rev') || null;

    return { project, path, rev, fromLine, toLine };
  } catch {
    return null;
  }
}

/**
 * Returns { project, hash } ONLY for internal commit links — a file-browser link with
 * a revision and no path:
 *   /files?rev=<hash>&project=kb                        (the form stored inside markdown)
 *   /files?project=kb&changes=1&rev=<hash>&right=commit (canonical, see urlScheme.commitUrl)
 *
 * The stored form names a commit by its hash (full, from a tool answer), and only a hex
 * hash is what the document export flattens (DocumentLinkRewriter). The parser itself
 * takes any revision: a hand-written link naming a branch still opens, since the Files
 * panel reads any revision. A link that also names a path is a file link (see
 * parseFileLink), not a commit one.
 */
export function parseCommitLink(href) {
  if (!href) return null;
  try {
    const url = new URL(href, window.location.origin);
    if (url.origin !== window.location.origin) return null;
    if (!/^\/files\/?$/.test(url.pathname) || filesLinkPath(url)) return null;
    const hash = url.searchParams.get('rev');
    if (!hash) return null;
    return { project: url.searchParams.get('project') || null, hash };
  } catch {
    return null;
  }
}

/** Path of a same-origin `/files` link in either form, or '' when it names none. */
function filesLinkPath(url) {
  if (url.pathname === '/files') return url.searchParams.get('path') || '';
  if (url.pathname.startsWith('/files/')) return decodeFilePath(url.pathname.slice('/files/'.length));
  return '';
}

/**
 * The stored form of a commit link — what goes into markdown, not what a browser opens
 * (that is urlScheme.commitUrl): `/files?rev=<hash>[&project=<id>]`, the same form the
 * model is told to write, so a link from a composer chip and one from an answer read alike.
 */
export function commitLinkTarget(hash, project) {
  const p = new URLSearchParams({ rev: hash });
  if (project) p.set('project', project);
  return `/files?${p.toString()}`;
}
