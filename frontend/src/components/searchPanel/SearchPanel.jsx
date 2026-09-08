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
  const { selected: picked, missing } = resolveProjectChoice(filters.project, projectOptions, defaultProjectId);
  // А вот в запрос и в ссылки уходит значение ИЗ АДРЕСА: пусто и означает
  // дефолтный проект — так устроена вся схема адресов. Подставить сюда его id
  // значило бы писать дефолт в ссылку на найденный файл, чего схема не делает;
  // заодно ссылка не ждёт загрузки конфигурации, чтобы стать правильной.
  const project = missing ? '' : filters.project;

  const results = useSearchResults({ query, mode, ...filters, project });
  const { entry, loading } = results[scope];

  const counts = useMemo(
    () => ({
      files: countOf(results.files.entry, 'files'),
      docs: countOf(results.docs.entry, 'documents'),
      chats: countOf(results.chats.entry, 'chats'),
    }),
    [results.files.entry, results.docs.entry, results.chats.entry],
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
              project={picked}
              projectOptions={projectOptions}
              // Дефолтный проект в адрес не пишем — его выбор это пустое значение.
              onProjectChange={(id) => onRefine({ searchProject: id === defaultProjectId ? '' : id })}
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
          loading={loading}
          entry={entry}
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
