import { useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import gitApi from '../../api/gitApi';
import PanelSearch from '../common/PanelSearch';
import { highlightFileMatch } from '../common/highlightMatch';
import { IconDoc } from '../../icons';

const RESULT_LIMIT = 15;

/**
 * Поиск файла по имени над деревом репозитория. Виджет целиком общий
 * (common/PanelSearch) — здесь только запрос и описание строки: имя файла с
 * подсветкой совпадения и каталог под ним.
 */
const FileSearch = ({ onSelect }) => {
  const { t } = useTranslation('files');

  const search = useCallback((q, signal) => gitApi.searchFiles(q, RESULT_LIMIT, signal), []);

  const describeItem = useCallback((node, query) => {
    const { name, dir } = highlightFileMatch(node.name, node.path, query);
    return { icon: <IconDoc size={13} />, title: name, subtitle: dir };
  }, []);

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
      minWidth={360}
    />
  );
};

export default FileSearch;
