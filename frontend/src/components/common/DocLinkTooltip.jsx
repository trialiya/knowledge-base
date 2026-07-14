import React, { useState, useRef, useCallback, useEffect } from 'react';
import { createPortal } from 'react-dom';
import { useTranslation } from 'react-i18next';
import useDocPreview from './useDocPreview';
import useFilePreview from './useFilePreview';
import FilePreviewModal from './FilePreviewModal';
import FullscreenEditorModal from '../knowledgeBasePanel/FullscreenEditorModal';
import { IconFolder, IconDoc, IconFileText, IconSparkle, IconExpand } from '../../icons';
import { TOOLTIP_WIDTH, TOOLTIP_GAP, TOOLTIP_HEIGHT_ESTIMATE } from '../../constants/ui';

/**
 * Wraps a `/?doc=N` link with a hover-activated preview tooltip. Shared by the
 * Knowledge Base markdown renderer (MarkdownEditor, has a `tree` for instant
 * lookups) and the chat message renderer (Message, no tree — always goes
 * through useDocPreview's module cache). Previously these were two
 * near-identical components (DocLinkTooltip / ChatDocLink); the chat copy was
 * missing the anchor-link branch below and hardcoded a ru-RU/en-US locale
 * instead of the active UI language.
 *
 * Four link kinds are handled:
 *   1. In-document anchors (`#обзор`) — scroll to the heading WITHIN the same
 *      rendered-markdown container, no navigation, no URL change. Heading ids
 *      are produced by rehype-slug in MarkdownEditor (GitHub slugger), matching
 *      the slugs in `[Обзор](#обзор)` tables of contents.
 *   2. Internal KB doc links (`/?doc=N`) — hover preview + click navigation
 *      (or fullscreen preview via the tooltip's expand button).
 *   3. Internal repo file links (`/files?path=P[#Lx-Ly]`) — hover preview +
 *      click opens a read-only FilePreviewModal in place, without navigating
 *      to FilesPanel.
 *   4. Everything else — plain external `<a target="_blank">`.
 *
 * Navigation for (2) goes through the `onNavigate` prop (in KB this is
 * selectNode, in chat it's openDoc from useAppNavigation) — both accept an id.
 */
const DocLinkTooltip = ({ href, children, tree = [], onNavigate, ...rest }) => {
  const [visible, setVisible] = useState(false);
  const [pos, setPos] = useState({ top: 0, left: 0 });
  // Снимок узла для fullscreen-превью: useDocPreview сбрасывает node, когда
  // тултип прячется (enabled=false), а модалка живёт дольше тултипа.
  const [fullscreenNode, setFullscreenNode] = useState(null);
  const [filePreviewOpen, setFilePreviewOpen] = useState(false);
  const enterTimer = useRef(null);
  const leaveTimer = useRef(null);
  const linkRef = useRef(null);
  const tooltipRef = useRef(null);

  const docId = parseDocId(href);
  const isDocLink = docId !== null;
  const fileLink = isDocLink ? null : parseFileLink(href);
  const isFileLink = fileLink !== null;
  const isHashLink = typeof href === 'string' && href.trim().startsWith('#');

  const { node, loading, error } = useDocPreview(docId, tree, visible && isDocLink);
  const { file, loading: fileLoading, error: fileError } = useFilePreview(fileLink?.path, visible && isFileLink);

  // ── Position ────────────────────────────────────────────────────────────

  const calcPos = useCallback(() => {
    if (!linkRef.current) return;
    const rect = linkRef.current.getBoundingClientRect();
    const left = Math.min(Math.max(rect.left, TOOLTIP_GAP), window.innerWidth - TOOLTIP_WIDTH - TOOLTIP_GAP);
    const tooltipH = tooltipRef.current ? tooltipRef.current.offsetHeight : TOOLTIP_HEIGHT_ESTIMATE;
    const spaceBelow = window.innerHeight - rect.bottom - TOOLTIP_GAP;

    const top =
      spaceBelow >= tooltipH || spaceBelow >= rect.top - TOOLTIP_GAP
        ? rect.bottom + TOOLTIP_GAP
        : rect.top - tooltipH - TOOLTIP_GAP;

    setPos({ top, left });
  }, []);

  // Re-measure after content arrives (height changes on load)
  useEffect(() => {
    if (visible) calcPos();
  }, [visible, node, loading, file, fileLoading, calcPos]);

  // ── Hover ───────────────────────────────────────────────────────────────

  const handleMouseEnter = useCallback(() => {
    clearTimeout(leaveTimer.current);
    enterTimer.current = setTimeout(() => {
      calcPos();
      setVisible(true);
    }, 180);
  }, [calcPos]);

  const handleMouseLeave = useCallback(() => {
    clearTimeout(enterTimer.current);
    leaveTimer.current = setTimeout(() => setVisible(false), 200);
  }, []);

  const keepOpen = useCallback(() => clearTimeout(leaveTimer.current), []);

  useEffect(
    () => () => {
      clearTimeout(enterTimer.current);
      clearTimeout(leaveTimer.current);
    },
    [],
  );

  // ── Navigate ─────────────────────────────────────────────────────────────
  // Навигация идёт через проп onNavigate (в KB это selectNode, в чате —
  // openDoc), оба принимают id документа.
  const navigateToDoc = useCallback(
    (id) => {
      onNavigate?.(id);
    },
    [onNavigate],
  );

  const handleClick = useCallback(
    (e) => {
      e.preventDefault();
      if (docId) navigateToDoc(docId);
    },
    [docId, navigateToDoc],
  );

  // Файловая ссылка не уходит из чата — клик всегда открывает read-only
  // модалку (FilePreviewModal), а не FilesPanel. Тултип при этом прячем,
  // иначе он остаётся смонтированным под оверлеем и «выглядывает» после
  // закрытия модалки.
  const openFilePreview = useCallback(() => {
    clearTimeout(enterTimer.current);
    setVisible(false);
    setFilePreviewOpen(true);
  }, []);

  const handleFileClick = useCallback(
    (e) => {
      e.preventDefault();
      openFilePreview();
    },
    [openFilePreview],
  );

  // То же для fullscreen-превью документа, открываемого из тултипа. Узел
  // снимается в отдельный стейт, потому что node из useDocPreview обнуляется
  // вместе с visible.
  const openFullscreen = useCallback((n) => {
    clearTimeout(enterTimer.current);
    setVisible(false);
    setFullscreenNode(n);
  }, []);

  // ── In-document anchor (#heading) ──────────────────────────────────────────
  // Scroll to the slugged heading within THIS rendered-markdown container.
  // STRICT matching: the anchor must equal the generated heading id verbatim
  // (rehype-slug / github-slugger). A mismatch (e.g. `#обзор` pointing at a
  // numbered `## 1. Обзор` whose id is `1-обзор`) intentionally does nothing —
  // it signals a bad anchor to fix in the source markdown, rather than being
  // silently "rescued". Container scoping avoids id clashes when an inline and
  // a fullscreen editor are mounted at once.
  const scrollToAnchor = useCallback(
    (e) => {
      e.preventDefault();
      const raw = (href || '').slice(1);
      if (!raw) return;
      let decoded = raw;
      try {
        decoded = decodeURIComponent(raw);
      } catch {
        /* keep raw */
      }
      const container = e.currentTarget.closest('.md-preview, .md-preview--embedded') || document;
      // Anchor targets are always headings; querying h1–h6[id] (instead of all
      // [id]) is both semantically correct and avoids matching any non-heading
      // element that might carry the same id.
      const target = Array.from(container.querySelectorAll('h1[id], h2[id], h3[id], h4[id], h5[id], h6[id]')).find(
        (el) => el.id === decoded || el.id === raw,
      );
      if (target) scrollToHeading(target);
    },
    [href],
  );

  // ── In-document anchor link ────────────────────────────────────────────────

  if (isHashLink) {
    return (
      <a href={href} className="doc-link doc-link--anchor" onClick={scrollToAnchor} {...rest}>
        {children}
      </a>
    );
  }

  // ── File link (/files?path=...) ────────────────────────────────────────────

  if (isFileLink) {
    return (
      <>
        <a
          ref={linkRef}
          href={href}
          className="doc-link"
          onClick={handleFileClick}
          onMouseEnter={handleMouseEnter}
          onMouseLeave={handleMouseLeave}
          {...rest}
        >
          {children}
        </a>

        {visible &&
          createPortal(
            <FilePreviewTooltip
              ref={tooltipRef}
              file={file}
              loading={fileLoading}
              error={fileError}
              pos={pos}
              onMouseEnter={keepOpen}
              onMouseLeave={handleMouseLeave}
              onOpen={openFilePreview}
            />,
            document.body,
          )}

        {filePreviewOpen && (
          <FilePreviewModal
            path={fileLink.path}
            fromLine={fileLink.fromLine}
            toLine={fileLink.toLine}
            onClose={() => setFilePreviewOpen(false)}
          />
        )}
      </>
    );
  }

  // ── Non-doc link ────────────────────────────────────────────────────────

  if (!isDocLink) {
    return (
      <a href={href} target="_blank" rel="noopener noreferrer" {...rest}>
        {children}
      </a>
    );
  }

  return (
    <>
      <a
        ref={linkRef}
        href={href}
        className="doc-link"
        onClick={handleClick}
        onMouseEnter={handleMouseEnter}
        onMouseLeave={handleMouseLeave}
        {...rest}
      >
        {children}
      </a>

      {visible &&
        createPortal(
          <DocPreviewTooltip
            ref={tooltipRef}
            node={node}
            loading={loading}
            error={error}
            pos={pos}
            onMouseEnter={keepOpen}
            onMouseLeave={handleMouseLeave}
            onNavigate={navigateToDoc}
            onExpand={openFullscreen}
          />,
          document.body,
        )}

      {fullscreenNode && (
        <FullscreenEditorModal
          title={fullscreenNode.title}
          value={fullscreenNode.description || ''}
          previewOnly
          tree={tree}
          onNavigate={navigateToDoc}
          onClose={() => setFullscreenNode(null)}
        />
      )}
    </>
  );
};

// ── Tooltip card ─────────────────────────────────────────────────────────────

const DocPreviewTooltip = React.forwardRef(
  ({ node, loading, error, pos, onMouseEnter, onMouseLeave, onNavigate, onExpand }, ref) => {
    const { t, i18n } = useTranslation('knowledgeBase');
    const isFolder = node?.type === 'folder';

    return (
      <div
        ref={ref}
        className="doc-preview-tooltip"
        style={{ top: pos.top, left: pos.left }}
        onMouseEnter={onMouseEnter}
        onMouseLeave={onMouseLeave}
      >
        {loading && (
          <div className="doc-preview-tooltip__loading">
            <span className="doc-preview-tooltip__spinner" />
            <span>{t('docLink.loading')}</span>
          </div>
        )}

        {error && !loading && <div className="doc-preview-tooltip__error">{t('docLink.notFound')}</div>}

        {node && !loading && (
          <>
            <div className="doc-preview-tooltip__header">
              <span className={`doc-preview-tooltip__icon${isFolder ? ' doc-preview-tooltip__icon--folder' : ''}`}>
                {isFolder ? <IconFolder size={13} /> : <IconDoc size={13} />}
              </span>
              <span className="doc-preview-tooltip__title">{node.title}</span>
              <span className={`doc-preview-tooltip__badge${isFolder ? ' doc-preview-tooltip__badge--folder' : ''}`}>
                {isFolder ? t('docLink.folder') : t('docLink.document')}
              </span>
              <button
                className="doc-preview-tooltip__expand"
                title={t('docLink.expand')}
                onClick={(e) => {
                  e.stopPropagation();
                  onExpand(node);
                }}
              >
                <IconExpand size={12} />
              </button>
            </div>

            {node.summary && (
              <div className="doc-preview-tooltip__summary">
                <span className="doc-preview-tooltip__summary-label">
                  <IconSparkle size={10} />
                  {t('docLink.aiSummary')}
                  {node.summaryStale && <span className="doc-preview-tooltip__stale">{t('docLink.stale')}</span>}
                </span>
                <p className="doc-preview-tooltip__summary-text">{node.summary}</p>
              </div>
            )}

            {!node.summary && node.description && (
              <div className="doc-preview-tooltip__description">
                <p className="doc-preview-tooltip__description-text">{snippetFromMarkdown(node.description, 200)}</p>
              </div>
            )}

            {!node.summary && !node.description && (
              <p className="doc-preview-tooltip__empty">{t('docLink.noDescription')}</p>
            )}

            <div className="doc-preview-tooltip__footer">
              <span className="doc-preview-tooltip__date">
                {node.updatedAt ? new Date(node.updatedAt).toLocaleDateString(i18n.language) : ''}
              </span>
              <button
                className="doc-preview-tooltip__open"
                onClick={(e) => {
                  e.stopPropagation();
                  onNavigate(node.id);
                }}
              >
                {t('docLink.open')}
              </button>
            </div>
          </>
        )}
      </div>
    );
  },
);

DocPreviewTooltip.displayName = 'DocPreviewTooltip';

// ── File tooltip card ────────────────────────────────────────────────────────

const FilePreviewTooltip = React.forwardRef(
  ({ file, loading, error, pos, onMouseEnter, onMouseLeave, onOpen }, ref) => {
    const { t } = useTranslation('knowledgeBase');
    const name = file?.path ? file.path.slice(file.path.lastIndexOf('/') + 1) : '';

    return (
      <div
        ref={ref}
        className="doc-preview-tooltip"
        style={{ top: pos.top, left: pos.left }}
        onMouseEnter={onMouseEnter}
        onMouseLeave={onMouseLeave}
      >
        {loading && (
          <div className="doc-preview-tooltip__loading">
            <span className="doc-preview-tooltip__spinner" />
            <span>{t('docLink.loading')}</span>
          </div>
        )}

        {error && !loading && <div className="doc-preview-tooltip__error">{t('files:file.loadError')}</div>}

        {file && !loading && (
          <>
            <div className="doc-preview-tooltip__header">
              <span className="doc-preview-tooltip__icon">
                <IconFileText size={13} />
              </span>
              <span className="doc-preview-tooltip__title">{name}</span>
              <span className="doc-preview-tooltip__badge">{t('docLink.file')}</span>
            </div>

            <div className="doc-preview-tooltip__description">
              <p className="doc-preview-tooltip__description-text doc-preview-tooltip__description-text--mono">
                {file.path}
              </p>
            </div>

            {file.binary ? (
              <p className="doc-preview-tooltip__empty">{t('docLink.binary')}</p>
            ) : (
              <div className="doc-preview-tooltip__description">
                <p className="doc-preview-tooltip__description-text doc-preview-tooltip__description-text--mono">
                  {snippetFromCode(file.content, 200)}
                </p>
              </div>
            )}

            <div className="doc-preview-tooltip__footer">
              <span className="doc-preview-tooltip__date">
                {file.language || ''}
                {file.language && ' · '}
                {t('files:file.lines', { count: file.lineCount })}
              </span>
              <button
                className="doc-preview-tooltip__open"
                onClick={(e) => {
                  e.stopPropagation();
                  onOpen();
                }}
              >
                {t('docLink.open')}
              </button>
            </div>
          </>
        )}
      </div>
    );
  },
);

FilePreviewTooltip.displayName = 'FilePreviewTooltip';

// ── Helpers ──────────────────────────────────────────────────────────────────

/** Nearest scrollable ancestor (the element whose scroll we must move). */
function getScrollParent(el) {
  let node = el.parentElement;
  while (node) {
    const { overflowY } = window.getComputedStyle(node);
    if ((overflowY === 'auto' || overflowY === 'scroll') && node.scrollHeight > node.clientHeight + 1) {
      return node;
    }
    node = node.parentElement;
  }
  return null;
}

/**
 * Scrolls the heading into view by moving its actual scroll container's
 * scrollTop, instead of relying on scrollIntoView() — which, in this layout
 * (body/#root locked with overflow:hidden), may try to scroll a non-scrollable
 * ancestor and visibly do nothing. Falls back to scrollIntoView if no
 * scrollable ancestor is found.
 */
function scrollToHeading(target) {
  const scroller = getScrollParent(target);
  if (scroller) {
    const tRect = target.getBoundingClientRect();
    const sRect = scroller.getBoundingClientRect();
    const top = scroller.scrollTop + (tRect.top - sRect.top) - 8;
    scroller.scrollTo({ top, behavior: 'smooth' });
  } else {
    target.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }
}

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
function parseDocId(href) {
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
function parseFileLink(href) {
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

/**
 * Plain-text snippet of source code: collapse whitespace and cut to maxLen. Unlike
 * snippetFromMarkdown, no markdown syntax is stripped — code isn't markdown, and those
 * regexes would mangle legitimate code (backticks, brackets, list-like operators).
 */
function snippetFromCode(text, maxLen) {
  if (!text) return '';
  const plain = text.replace(/\s+/g, ' ').trim();
  return plain.length > maxLen ? plain.slice(0, maxLen) + '…' : plain;
}

function snippetFromMarkdown(md, maxLen) {
  if (!md) return '';
  const plain = md
    .replace(/#{1,6}\s/g, '')
    .replace(/\*\*(.+?)\*\*/g, '$1')
    .replace(/__(.+?)__/g, '$1')
    .replace(/\*(.+?)\*/g, '$1')
    .replace(/_(.+?)_/g, '$1')
    .replace(/`(.+?)`/g, '$1')
    .replace(/\[(.+?)]\(.+?\)/g, '$1')
    .replace(/>\s/g, '')
    .replace(/[-*+]\s/g, '')
    .replace(/\n+/g, ' ')
    .trim();
  return plain.length > maxLen ? plain.slice(0, maxLen) + '…' : plain;
}

export default DocLinkTooltip;
