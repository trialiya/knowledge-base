import { useTranslation } from 'react-i18next';
import PreviewTooltipShell from './PreviewTooltipShell';
import { IconCommit } from '@/icons/index';
import { formatDateTime } from '@/utils/formatting';

/**
 * Карточка ссылки на коммит (CommitLink): название, хеш с автором и датой, начало
 * описания и сводка изменений — то же, что вкладка «Коммит» в «Файлах», только
 * коротко. `commit` — GitCommit с `files` (ответ `GET /api/git/commit`).
 */
function CommitPreviewTooltip({ commit, loading, error, pos, onMouseEnter, onMouseLeave, onOpen, ref }) {
  const { t, i18n } = useTranslation('knowledgeBase');
  const files = commit?.files ?? [];
  const additions = files.reduce((sum, file) => sum + file.additions, 0);
  const deletions = files.reduce((sum, file) => sum + file.deletions, 0);
  const byline = commit ? [commit.shortHash, commit.author, formatDateTime(commit.date, i18n.language)] : [];

  return (
    <PreviewTooltipShell
      ref={ref}
      loading={loading}
      error={error}
      errorLabel={t('docLink.commitNotFound')}
      hasContent={!!commit}
      pos={pos}
      onMouseEnter={onMouseEnter}
      onMouseLeave={onMouseLeave}
    >
      {commit && (
        <>
          <div className="doc-preview-tooltip__header">
            <span className="doc-preview-tooltip__icon">
              <IconCommit size={13} />
            </span>
            <span className="doc-preview-tooltip__title" title={commit.message}>
              {commit.message}
            </span>
            <span className="doc-preview-tooltip__badge">{t('docLink.commit')}</span>
          </div>

          <div className="doc-preview-tooltip__description">
            <p className="doc-preview-tooltip__description-text doc-preview-tooltip__description-text--mono">
              {byline.filter(Boolean).join(' · ')}
            </p>
          </div>

          {commit.body && (
            <div className="doc-preview-tooltip__description">
              <p className="doc-preview-tooltip__description-text">{commit.body}</p>
            </div>
          )}

          <div className="doc-preview-tooltip__footer">
            <span className="doc-preview-tooltip__date">
              {t('files:commit.filesSummary', { count: files.length, additions, deletions })}
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
}

export default CommitPreviewTooltip;
