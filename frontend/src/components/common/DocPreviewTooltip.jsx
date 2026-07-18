import React from 'react';
import { useTranslation } from 'react-i18next';
import { IconFolder, IconDoc, IconSparkle, IconExpand } from '../../icons';

/** Nested preview card for DocLinkTooltip's internal KB doc-link branch. */
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

export default DocPreviewTooltip;
