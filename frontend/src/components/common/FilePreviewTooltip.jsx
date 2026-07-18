import React from 'react';
import { useTranslation } from 'react-i18next';
import PreviewTooltipShell from './PreviewTooltipShell';
import { baseName } from './utils';
import { IconFileText, IconExpand } from '../../icons';

/** Nested preview card for DocLinkTooltip's internal repo file-link branch. */
const FilePreviewTooltip = React.forwardRef(
  ({ file, loading, error, pos, onMouseEnter, onMouseLeave, onOpen, onExpand }, ref) => {
    const { t } = useTranslation('knowledgeBase');
    const name = baseName(file?.path);

    return (
      <PreviewTooltipShell
        ref={ref}
        loading={loading}
        error={error}
        errorLabel={t('files:file.loadError')}
        hasContent={!!file}
        pos={pos}
        onMouseEnter={onMouseEnter}
        onMouseLeave={onMouseLeave}
      >
        {file && (
          <>
            <div className="doc-preview-tooltip__header">
              <span className="doc-preview-tooltip__icon">
                <IconFileText size={13} />
              </span>
              <span className="doc-preview-tooltip__title">{name}</span>
              <span className="doc-preview-tooltip__badge">{t('docLink.file')}</span>
              <button
                className="doc-preview-tooltip__expand"
                title={t('docLink.expand')}
                onClick={(e) => {
                  e.stopPropagation();
                  onExpand(file);
                }}
              >
                <IconExpand size={12} />
              </button>
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
      </PreviewTooltipShell>
    );
  },
);

FilePreviewTooltip.displayName = 'FilePreviewTooltip';

/**
 * Plain-text snippet of source code: collapse whitespace and cut to maxLen. Unlike
 * markdown snippets, no markdown syntax is stripped — code isn't markdown, and those
 * regexes would mangle legitimate code (backticks, brackets, list-like operators).
 */
function snippetFromCode(text, maxLen) {
  if (!text) return '';
  const plain = text.replace(/\s+/g, ' ').trim();
  return plain.length > maxLen ? plain.slice(0, maxLen) + '…' : plain;
}

export default FilePreviewTooltip;
