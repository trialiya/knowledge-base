import React from 'react';
import { useTranslation } from 'react-i18next';

/**
 * Shared chrome for DocPreviewTooltip/FilePreviewTooltip: positioned card +
 * loading/error states. Callers supply only the loaded-content markup
 * (header/body/footer differ per link kind) via children.
 */
const PreviewTooltipShell = React.forwardRef(
  ({ loading, error, errorLabel, hasContent, pos, onMouseEnter, onMouseLeave, children }, ref) => {
    const { t } = useTranslation('knowledgeBase');

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

        {error && !loading && <div className="doc-preview-tooltip__error">{errorLabel}</div>}

        {hasContent && !loading && children}
      </div>
    );
  },
);

PreviewTooltipShell.displayName = 'PreviewTooltipShell';

export default PreviewTooltipShell;
