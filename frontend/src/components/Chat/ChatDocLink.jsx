import React, { useState, useRef, useCallback, useEffect } from 'react';
import { createPortal } from 'react-dom';
import useDocPreview from '../KnowledgeBase/useDocPreview';
import { IconFolder, IconDoc, IconSparkle } from '../KnowledgeBase/icons';

/**
 * Обёртка для внутренних KB-ссылок (`/?doc=N`) внутри сообщений чата.
 *
 * Отличия от KnowledgeBase/DocLinkTooltip:
 *   • дерева KB в чате нет → useDocPreview грузит документ через api.fetchById (tree=[]);
 *   • навигация идёт через проп onNavigateToDoc (он же переключает вкладку на «База знаний»
 *     и диспатчит app:navigate-doc), а не через прямой window.dispatchEvent.
 *
 * Внешние ссылки рендерятся как обычный <a target="_blank">.
 */
const ChatDocLink = ({ href, children, onNavigateToDoc, ...rest }) => {
  const [visible, setVisible] = useState(false);
  const [pos, setPos] = useState({ top: 0, left: 0 });
  const enterTimer = useRef(null);
  const leaveTimer = useRef(null);
  const linkRef = useRef(null);
  const tooltipRef = useRef(null);

  const docId = parseDocId(href);
  const isDocLink = docId !== null;

  // tree=[] — в чате нет локального дерева, грузим по API
  const { node, loading, error } = useDocPreview(docId, [], visible && isDocLink);

  // ── Позиционирование ───────────────────────────────────────────────────
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

  useEffect(() => {
    if (visible) calcPos();
  }, [visible, node, loading, calcPos]);

  // ── Hover ────────────────────────────────────────────────────────────────
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

  // ── Навигация ──────────────────────────────────────────────────────────
  const navigateToDoc = useCallback(
    (id) => {
      if (onNavigateToDoc) {
        onNavigateToDoc(String(id));
      } else {
        // Фолбэк: если проп не передан, ведём себя как KB-тултип
        window.dispatchEvent(new CustomEvent('app:navigate-doc', { detail: { docId: String(id) } }));
      }
    },
    [onNavigateToDoc],
  );

  const handleClick = useCallback(
    (e) => {
      e.preventDefault();
      if (docId) navigateToDoc(docId);
    },
    [docId, navigateToDoc],
  );

  // ── Внешняя ссылка ───────────────────────────────────────────────────────
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

// ── Карточка превью (идентична KB по разметке и классам) ───────────────────────
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

DocPreviewTooltip.displayName = 'ChatDocPreviewTooltip';

// ── Helpers (идентичны KB) ─────────────────────────────────────────────────────

/**
 * Возвращает id документа ТОЛЬКО для внутренних KB-ссылок того же origin:
 *   /?doc=123 · ?doc=123 · /kb?doc=123 · https://<this-site>/?doc=123
 * Для внешних URL (другой origin) → null (рендерим как внешнюю <a>).
 */
function parseDocId(href) {
  if (!href) return null;
  try {
    const url = new URL(href, window.location.origin);
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

export default ChatDocLink;
