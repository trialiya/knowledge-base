import { useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import gitApi from '@/api/gitApi';
import PanelSearch from '@/components/common/search/PanelSearch';
import { highlightFileMatch } from '@/components/common/search/highlightMatch';
import { IconDoc } from '@/icons/index';

const RESULT_LIMIT = 15;

/**
 * Поиск файла по имени над деревом репозитория. Виджет целиком общий
 * (common/PanelSearch) — здесь только запрос и описание строки: имя файла с
 * подсветкой совпадения и каталог под ним.
 */
const FileSearch = ({ project, onSelect }) => {
  const { t } = useTranslation('files');

  // project в зависимостях обязателен: с пустым списком колбэк застрял бы на
  // прежнем репозитории и искал бы файлы не там, куда смотрит панель.
  const search = useCallback((q, signal) => gitApi.searchFiles(q, { limit: RESULT_LIMIT, project, signal }), [project]);

  // Файл вне git помечен тёплой заливкой — тем же оттенком, что и строка дерева
  // (см. filesPanel.css): найденный и открытый файл должны читаться одинаково.
  const describeItem = useCallback(
    (node, query) => {
      const { name, dir } = highlightFileMatch(node.name, node.path, query);
      const untracked = node.tracked === false;
      return {
        icon: <IconDoc size={13} />,
        title: name,
        subtitle: dir,
        tone: untracked ? 'warn' : undefined,
        toneLabel: untracked ? t('tree.untracked') : undefined,
      };
    },
    [t],
  );

  const choose = useCallback((node) => onSelect(node.path), [onSelect]);

  return (
    <PanelSearch
      label={t('search.open')}
      placeholder={t('search.placeholder')}
      hint={t('search.hint')}
      search={search}
      describeItem={describeItem}
      getKey={(node) => node.path}
      onSelect={choose}
      // Шире, чем у поиска по чатам: в строке стоит имя файла, а под ним — путь
      // от корня репозитория, и на java-пакетах оба не влезали в 360px.
      minWidth={520}
    />
  );
};

export default FileSearch;
