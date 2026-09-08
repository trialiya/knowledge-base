import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import WorkspaceLayout from '@/components/common/layout/WorkspaceLayout';
import useProjectConfig from '@/components/common/config/useProjectConfig';
import { resolveProjectChoice } from '@/components/common/config/projectChoice';
import useSearchResults from './useSearchResults';
import SearchScopeList from './SearchScopeList';
import SearchFilters from './SearchFilters';
import ResultList from './results/ResultList';
import './searchPanel.css';

/** Сколько сущностей нашла категория — это и есть её счётчик в левой панели. */
function countOf(entry, field) {
  return entry?.data ? entry.data[field].length : null;
}

/**
 * Единый поиск: запрос из шапки приложения, категория и её фильтры слева,
 * результаты по сущностям в центре.
 *
 * Раздел свой, а не вкладка внутри чужого: искать одинаково нужно и из чата, и
 * из базы знаний, и из файлов, а показывать найденное деревом документов или
 * деревом репозитория нечем — у каждой категории своя форма результата.
 *
 * Запрос и все фильтры живут в адресе (см. navUrl.js): выдачей делятся ссылкой,
 * а «Назад» возвращает к тому, что искали до этого.
 */
const SearchPanel = ({ query, scope, mode, filters, onRefine, onOpenFile, onOpenDoc, onOpenChat, panels }) => {
  const { t } = useTranslation('search');
  const { projectOptions, defaultProjectId } = useProjectConfig();
  // Проект из адреса может уже не существовать — тогда ищем в дефолтном, как и
  // панель «Файлы»: 400 вместо результатов не объяснил бы ничего.
  const { selected: project } = resolveProjectChoice(filters.project, projectOptions, defaultProjectId);

  const results = useSearchResults({ query, mode, ...filters, project });

  const counts = useMemo(
    () => ({
      files: countOf(results.files, 'files'),
      docs: countOf(results.docs, 'documents'),
      chats: countOf(results.chats, 'chats'),
    }),
    [results.files, results.docs, results.chats],
  );

  return (
    <WorkspaceLayout
      {...panels}
      left={{
        title: t('panel.title'),
        children: (
          <>
            <SearchScopeList scope={scope} counts={counts} onSelect={(next) => onRefine({ searchScope: next })} />
            <SearchFilters
              scope={scope}
              path={filters.path}
              project={project}
              projectOptions={projectOptions}
              rev={filters.rev}
              regex={filters.regex}
              untracked={filters.untracked}
              mode={mode}
              onRefine={onRefine}
            />
          </>
        ),
      }}
      center={
        <ResultList
          scope={scope}
          query={query}
          loading={results.loading}
          entry={results[scope]}
          regex={filters.regex}
          rev={filters.rev}
          project={project}
          onOpenFile={onOpenFile}
          onOpenDoc={onOpenDoc}
          onOpenChat={onOpenChat}
        />
      }
    />
  );
};

export default SearchPanel;
