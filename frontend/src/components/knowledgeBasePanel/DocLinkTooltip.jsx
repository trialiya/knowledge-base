import React, { useState, useRef, useCallback, useEffect } from 'react';
import { createPortal } from 'react-dom';
import { useTranslation } from 'react-i18next';
import useDocPreview from './useDocPreview';
import { IconFolder, IconDoc, IconSparkle } from './icons';
import { TOOLTIP_WIDTH, TOOLTIP_GAP, TOOLTIP_HEIGHT_ESTIMATE } from '../../constants/ui';

/**
 * Wraps a `/?doc=N` link with a hover-activated preview tooltip.
 *
 * Three link kinds are handled:
 *   1. In-document anchors (`#обзор`) — scroll to the heading WITHIN the same
 *      rendered-markdown container, no navigation, no URL change. Heading ids
 *      are produced by rehype-slug in MarkdownEditor (GitHub slugger), matching
 *      the slugs in `[Обзор](#обзор)` tables of contents.
 *   2. Internal KB doc links (`/?doc=N`) — hover preview + click navigation.
 *   3. Everything else — plain external `<a target="_blank">`.
 *
 * Navigation for (2) goes through the `onNavigate` prop (in KB this is
 * selectNode, which accepts an id). A custom-event fallback is kept for
 * backward compatibility if onNavigate isn't passed.
 */
const DocLinkTooltip = ({ href, children, tree, onNavigate, ...rest }) => {
  const [visible, setVisible] = useState(false);
  const [pos, setPos] = useState({ top: 0, left: 0 });
  const enterTimer = useRef(null);
  const leaveTimer = useRef(null);
  const linkRef = useRef(null);
  const tooltipRef = useRef(null);

  const docId = parseDocId(href);
  const isDocLink = docId !== null;
  const isHashLink = typeof href === 'string' && href.trim().startsWith('#');

  const { node, loading, error } = useDocPreview(docId, tree, visible && isDocLink);

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
  }, [visible, node, loading, calcPos]);

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
  // Навигация идёт через проп onNavigate (в KB это selectNode, принимающий id).
  // selectNode → fetchFullAndSelect(id, {notify:true}) → документ грузится,
  // выбирается, и useAppNavigation обновляет URL. Фолбэк на событие оставлен
  // для обратной совместимости, если onNavigate почему-то не передан.
  const navigateToDoc = useCallback(
    (id) => {
      if (onNavigate) {
        onNavigate(id);
      } else {
        window.dispatchEvent(new CustomEvent('app:navigate-doc', { detail: { docId: id } }));
      }
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
          />,
          document.body,
        )}
    </>
  );
};

// ── Tooltip card ─────────────────────────────────────────────────────────────

const DocPreviewTooltip = React.forwardRef(
  ({ node, loading, error, pos, onMouseEnter, onMouseLeave, onNavigate }, ref) => {
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
