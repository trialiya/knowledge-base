import { useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import documentsApi from '../../api/documentsApi';
import PanelSearch from '../common/PanelSearch';
import { highlightSubstring } from '../common/highlightMatch';
import { IconFolder, IconDoc } from '../../icons';

const RESULT_LIMIT = 15;
const SNIPPET_LIMIT = 80;

/** Первая строка описания без markdown-разметки — как подзаголовок результата. */
const plainSnippet = (description) => {
  if (!description) return null;
  const text = description
    .replace(/[#*_`>[\]]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
  if (!text) return null;
  return text.length > SNIPPET_LIMIT ? `${text.slice(0, SNIPPET_LIMIT)}…` : text;
};

/**
 * Быстрый поиск документа/папки по названию над деревом базы знаний.
 *
 * Это НЕ полнотекстовый поиск из шапки приложения (GlobalSearch, семантика +
 * ключевые слова, результаты в центре) — здесь то же, что у чатов и файлов:
 * добежать по имени до узла и открыть его, не раскрывая дерево вручную.
 * Ходит в уже существующий search-by-name, бэкенд для этого не менялся.
 */
const TreeSearch = ({ onSelect }) => {
  const { t } = useTranslation('knowledgeBase');

  const search = useCallback((q, signal) => documentsApi.searchByName(q, RESULT_LIMIT, signal), []);

  const describeItem = useCallback(
    (node, query) => ({
      icon: node.type === 'folder' ? <IconFolder size={13} /> : <IconDoc size={13} />,
      title: highlightSubstring(node.title, query),
      subtitle: plainSnippet(node.description),
    }),
    [],
  );

  const choose = useCallback((node) => onSelect(node.id), [onSelect]);

  return (
    <PanelSearch
      label={t('treeSearch.open')}
      placeholder={t('treeSearch.placeholder')}
      hint={t('treeSearch.hint')}
      search={search}
      describeItem={describeItem}
      getKey={(node) => node.id}
      onSelect={choose}
    />
  );
};

export default TreeSearch;
