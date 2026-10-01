import { Fragment } from 'react';
import { useTranslation } from 'react-i18next';
import { IconDoc, IconFolder, IconChevronRight, IconSparkle } from '@/icons/index';
import { docPath } from '@/navigation/urlScheme';
import AppLink from '@/components/common/ui/AppLink';
import RelativeTime from '@/components/common/ui/RelativeTime';

// Хлебные крошки строятся из parentList, который приходит с бэка вместе с
// результатом (корень → непосредственный родитель, без самого документа).
const ResultBreadcrumb = ({ parents }) => {
  if (!parents || parents.length === 0) return null;

  return (
    <div className="sr-breadcrumb">
      {parents.map((node, i) => (
        <Fragment key={node.id}>
          <span className="sr-breadcrumb__icon">
            <IconFolder size={11} />
          </span>
          <span className="sr-breadcrumb__name">{node.title}</span>
          {i < parents.length - 1 && (
            <span className="sr-breadcrumb__sep">
              <IconChevronRight size={9} />
            </span>
          )}
        </Fragment>
      ))}
    </div>
  );
};

const SearchResults = ({ query, results, onSelect }) => {
  const { t } = useTranslation('knowledgeBase');

  return (
    <div className="sr-panel">
      <div className="sr-header">
        <h3 className="sr-header__title">
          {t('search.title')}
          <span className="sr-header__query">«{query}»</span>
          <span className="sr-header__count">{results.length}</span>
        </h3>
      </div>

      <div className="sr-list">
        {results.length === 0 ? (
          <p className="sr-empty">{t('search.empty')}</p>
        ) : (
          results.map((res) => (
            <div key={res.id} className="sr-card">
              <div className="sr-card__head">
                <span className="sr-card__icon">
                  <IconDoc size={14} />
                </span>
                {/* Переход — только по имени. */}
                <AppLink className="sr-card__title" href={docPath(res.id)} onNavigate={() => onSelect(res.id)}>
                  {res.title}
                </AppLink>
                <RelativeTime className="sr-card__date" value={res.updatedAt} />
              </div>
              <ResultBreadcrumb parents={res.parentList} />
              {res.summary && (
                <div className="sr-card__summary">
                  <span className="doc-preview-tooltip__summary-label">
                    <IconSparkle size={11} />
                    {t('search.aiSummary')}
                  </span>
                  <p className="doc-preview-tooltip__summary-text">{res.summary}</p>
                </div>
              )}
              {res.snippet && <p className="sr-card__snippet">{res.snippet}</p>}
            </div>
          ))
        )}
      </div>
    </div>
  );
};

export default SearchResults;
