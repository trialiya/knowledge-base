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

/** «Идёт поиск» словами и волчком: одно и то же и до первой выдачи, и поверх неё. */
const Searching = ({ className }) => {
  const { t } = useTranslation('search');
  return (
    <p className={className} role="status">
      <span className="search-spinner" />
      {t('empty.searching')}
    </p>
  );
};

/**
 * Центр раздела: результаты выбранной категории, сгруппированные по сущности.
 *
 * Пока ответ на новый запрос не пришёл, на экране остаётся предыдущий — с
 * пометкой в шапке. Мигание пустотой между двумя выдачами читается как «ничего
 * не нашлось», хотя поиск ещё идёт.
 *
 * Но и молча оставлять прежнее нельзя: сменивший репозиторий видит выдачу,
 * которая к нему уже не относится, и без пометки читает её как ответ. Поэтому
 * устаревшая выдача гаснет и перестаёт нажиматься — ссылки в ней ведут в
 * репозиторий и ревизию, которые фильтры уже сменили, — а рядом со счётчиком
 * крутится волчок.
 */
const ResultList = ({ scope, query, loading, entry, regex, rev, project, onOpenFile, onOpenDoc, onOpenChat }) => {
  const { t } = useTranslation('search');

  if (!query) return <p className="search-results__hint">{t('empty.noQuery')}</p>;
  if (!entry) return <Searching className="search-results__hint search-results__hint--busy" />;
  if (entry.error) return <p className="search-results__error">{errorMessage(t, entry.error)}</p>;
  if (!entry.data) return <Searching className="search-results__hint search-results__hint--busy" />;

  const data = entry.data;
  const { total, groups } = summarize(scope, data);

  return (
    <div className={`search-results${loading ? ' search-results--stale' : ''}`} aria-busy={loading}>
      <div className="search-results__head">
        <span className="search-results__count">{t('head.matches', { count: total })}</span>
        <span className="search-results__in">{t(`head.in.${scope}`, { count: groups })}</span>
        {loading && <Searching className="search-results__pending" />}
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
