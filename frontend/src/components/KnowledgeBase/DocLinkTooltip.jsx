import React, { useState, useRef, useCallback, useEffect } from 'react';
import { createPortal } from 'react-dom';
import useDocPreview from './useDocPreview';
import { IconFolder, IconDoc, IconSparkle } from './icons';

/**
 * Wraps a `/?doc=N` link with a hover-activated preview tooltip.
 *
 * Navigation: fires `window.dispatchEvent(new CustomEvent('app:navigate-doc', { detail: { docId } }))`
 * which is already handled by useKnowledgeBase — it loads the full ancestor
 * chain, marks nodes with _openOnLoad, and selects the document.
 * This is the same path used by direct URL links, so the tree always
 * expands to reveal the target node.
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

  const { node, loading, error } = useDocPreview(docId, tree, visible && isDocLink);

  // ── Position ────────────────────────────────────────────────────────────

  const calcPos = useCallback(() => {
    if (!linkRef.current) return;
    const rect = linkRef.current.getBoundingClientRect();
    const GAP = 8;
    const W = 300;

    const left = Math.min(Math.max(rect.left, 8), window.innerWidth - W - 8);
    const tooltipH = tooltipRef.current ? tooltipRef.current.offsetHeight : 160;
    const spaceBelow = window.innerHeight - rect.bottom - GAP;

    const top = spaceBelow >= tooltipH || spaceBelow >= rect.top - GAP ? rect.bottom + GAP : rect.top - tooltipH - GAP;

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
  // Uses the global event bus so useKnowledgeBase runs navigateToDocById —
  // which loads ancestors, marks _openOnLoad, and reveals the node in the tree.

  const navigateToDoc = useCallback((id) => {
    window.dispatchEvent(new CustomEvent('app:navigate-doc', { detail: { docId: String(id) } }));
  }, []);

  const handleClick = useCallback(
    (e) => {
      e.preventDefault();
      if (docId) navigateToDoc(docId);
    },
    [docId, navigateToDoc],
  );

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
            <span>Загрузка…</span>
          </div>
        )}

        {error && !loading && <div className="doc-preview-tooltip__error">Документ не найден</div>}

        {node && !loading && (
          <>
            <div className="doc-preview-tooltip__header">
              <span className={`doc-preview-tooltip__icon${isFolder ? ' doc-preview-tooltip__icon--folder' : ''}`}>
                {isFolder ? <IconFolder size={13} /> : <IconDoc size={13} />}
              </span>
              <span className="doc-preview-tooltip__title">{node.title}</span>
              <span className={`doc-preview-tooltip__badge${isFolder ? ' doc-preview-tooltip__badge--folder' : ''}`}>
                {isFolder ? 'Folder' : 'Document'}
              </span>
            </div>

            {node.summary && (
              <div className="doc-preview-tooltip__summary">
                <span className="doc-preview-tooltip__summary-label">
                  <IconSparkle size={10} />
                  AI Summary
                  {node.summaryStale && <span className="doc-preview-tooltip__stale">устарел</span>}
                </span>
                <p className="doc-preview-tooltip__summary-text">{node.summary}</p>
              </div>
            )}

            {!node.summary && node.description && (
              <div className="doc-preview-tooltip__description">
                <p className="doc-preview-tooltip__description-text">{snippetFromMarkdown(node.description, 200)}</p>
              </div>
            )}

            {!node.summary && !node.description && <p className="doc-preview-tooltip__empty">Нет описания</p>}

            <div className="doc-preview-tooltip__footer">
              <span className="doc-preview-tooltip__date">
                {node.updatedAt ? new Date(node.updatedAt).toLocaleDateString('ru-RU') : ''}
              </span>
              <button
                className="doc-preview-tooltip__open"
                onClick={(e) => {
                  e.stopPropagation();
                  onNavigate(node.id);
                }}
              >
                Открыть →
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
    return doc && /^\d+$/.test(doc) ? doc : null;
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
