import { useTranslation } from 'react-i18next';
import { SEARCH_SCOPE } from '@/constants/searchScope';
import FileResults from './FileResults';
import DocResults from './DocResults';
import ChatResults from './ChatResults';

/** Отказ категории словами, по которым понятно, что чинить. */
function errorMessage(t, error) {
  // 400 отдают ровно фильтры: битая регулярка, неизвестная ревизия, недопустимая
  // маска пути. 503 — git grep не уложился в отведённое ему время.
  if (error?.status === 400) return t('error.badFilter');
  if (error?.status === 503) return t('error.timeout');
  return t('error.generic');
}

/**
 * Сколько всего совпало и во скольких сущностях: у каждой категории свой ответ,
 * но вопрос один, поэтому счётчик в шапке считается тут, а не в трёх местах.
 */
function summarize(scope, data) {
  if (!data) return { total: 0, groups: 0 };
  if (scope === SEARCH_SCOPE.FILES) return { total: data.total, groups: data.files.length };
  if (scope === SEARCH_SCOPE.DOCS) return { total: data.total, groups: data.documents.length };
  return { total: data.total, groups: data.chats.length };
}

/**
 * Центр раздела: результаты выбранной категории, сгруппированные по сущности.
 *
 * Пока ответ на новый запрос не пришёл, на экране остаётся предыдущий — с
 * пометкой в шапке. Мигание пустотой между двумя выдачами читается как «ничего
 * не нашлось», хотя поиск ещё идёт.
 */
const ResultList = ({ scope, query, loading, entry, regex, rev, project, onOpenFile, onOpenDoc, onOpenChat }) => {
  const { t } = useTranslation('search');

  if (!query) return <p className="search-results__hint">{t('empty.noQuery')}</p>;
  if (!entry) return <p className="search-results__hint">{t('empty.searching')}</p>;
  if (entry.error) return <p className="search-results__error">{errorMessage(t, entry.error)}</p>;
  if (!entry.data) return <p className="search-results__hint">{t('empty.searching')}</p>;

  const data = entry.data;
  const { total, groups } = summarize(scope, data);

  return (
    <div className="search-results">
      <div className="search-results__head">
        <span className="search-results__count">{t('head.matches', { count: total })}</span>
        <span className="search-results__in">{t(`head.in.${scope}`, { count: groups })}</span>
        {loading && <span className="search-results__pending">{t('empty.searching')}</span>}
      </div>

      {data.truncated && <p className="search-results__note">{t(`truncated.${scope}`)}</p>}

      {groups === 0 ? (
        <p className="search-results__hint">{t('empty.noResults')}</p>
      ) : (
        <div className="search-results__list">
          {scope === SEARCH_SCOPE.FILES && (
            <FileResults
              result={data}
              query={query}
              regex={regex}
              rev={rev}
              project={project}
              onOpenFile={onOpenFile}
            />
          )}
          {scope === SEARCH_SCOPE.DOCS && <DocResults result={data} query={query} onOpenDoc={onOpenDoc} />}
          {scope === SEARCH_SCOPE.CHATS && <ChatResults result={data} query={query} onOpenChat={onOpenChat} />}
        </div>
      )}
    </div>
  );
};

export default ResultList;
