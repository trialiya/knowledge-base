import { Fragment } from 'react';
import { useTranslation } from 'react-i18next';
import { IconDoc, IconFolder, IconChevronRight } from '@/icons/index';
import { docPath } from '@/navigation/urlScheme';
import { highlightSubstring } from '@/components/common/search/highlightMatch';
import ResultGroup from './ResultGroup';

/** Путь до документа: приходит с ответом (корень → родитель, без самого документа). */
const Breadcrumb = ({ parents }) => (
  <span className="search-group__crumbs">
    {parents.map((node, i) => (
      <Fragment key={node.id}>
        <IconFolder size={11} />
        <span className="search-group__crumb">{node.title}</span>
        {i < parents.length - 1 && <IconChevronRight size={9} />}
      </Fragment>
    ))}
  </span>
);

/**
 * Совпадения в документах базы знаний: карточка на документ, внутри — фрагменты.
 *
 * У фрагмента бывает раздел (`sectionPath`) — заголовки markdown над строкой,
 * по ним понятно, где именно в документе нашлось. Фрагмент без номера строки
 * пришёл не из тела, а сниппетом ранжирующего поиска: семантика и гибрид
 * находят документ по смыслу, где искомой подстроки в тексте может и не быть.
 */
const DocResults = ({ result, query, onOpenDoc }) => {
  const { t, i18n } = useTranslation('search');

  return result.documents.map((doc) => (
    <ResultGroup
      key={doc.id}
      icon={<IconDoc size={14} />}
      title={highlightSubstring(doc.title, query)}
      href={docPath(doc.id)}
      onOpen={() => onOpenDoc(doc.id)}
      meta={doc.updatedAt ? new Date(doc.updatedAt).toLocaleDateString(i18n.language) : null}
      subtitle={doc.parentList?.length > 0 && <Breadcrumb parents={doc.parentList} />}
      rows={doc.fragments.map((fragment, i) => ({
        key: fragment.line == null ? `s${i}` : `l${fragment.line}`,
        node: (
          <>
            <span className="search-line__where" title={fragment.sectionPath || undefined}>
              {fragment.sectionPath || t('docs.snippet')}
            </span>
            <span className="search-line__text">{highlightSubstring(fragment.text, query)}</span>
          </>
        ),
      }))}
    />
  ));
};

export default DocResults;
