import { Fragment } from 'react';
import { useTranslation } from 'react-i18next';
import { IconDoc, IconFolder, IconChevronRight, IconH1 } from '@/icons/index';
import { docUrl } from '@/navigation/urlScheme';
import { PREAMBLE_PATH } from '@/components/common/preview/sectionAnchor';
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
 * Фрагменты по разделам, в порядке документа. Бэкенд отдаёт их по порядку
 * текста, и у строки стоит самый глубокий раздел, так что одинаковые пути
 * всегда идут подряд: группа — это соседи с равным `sectionPath`.
 */
export function groupBySection(fragments) {
  const groups = [];
  for (const fragment of fragments) {
    const last = groups[groups.length - 1];
    if (last && last.sectionPath === fragment.sectionPath) last.fragments.push(fragment);
    else groups.push({ sectionPath: fragment.sectionPath, fragments: [fragment] });
  }
  return groups;
}

/**
 * Совпадения в документах базы знаний: карточка на документ, внутри —
 * фрагменты, сгруппированные по разделам (заголовки markdown над строкой).
 *
 * Раздел — ссылка на документ с запросом и путём раздела в адресе: find-бар
 * там откроется сам и встанет на первое совпадение этого раздела, а не
 * документа. Строки под ним ведут туда же. Фрагмент без номера строки пришёл
 * не из тела, а сниппетом ранжирующего поиска: семантика и гибрид находят
 * документ по смыслу, где искомой подстроки в тексте может и не быть, — у
 * такого документа запроса в адресе нет, бар открылся бы с честным «0/0».
 */
const DocResults = ({ result, query, onOpenDoc }) => {
  const { t, i18n } = useTranslation('search');

  return result.documents.map((doc) => {
    const fromBody = doc.fragments.some((fragment) => fragment.line != null);
    const find = fromBody ? query : '';
    const sectionLabel = (path) => (path === PREAMBLE_PATH ? t('docs.preamble') : path);
    const rows = groupBySection(doc.fragments).flatMap(({ sectionPath, fragments }) => {
      const target = { find, section: sectionPath && sectionPath !== PREAMBLE_PATH ? sectionPath : '' };
      const link = fromBody ? { href: docUrl(doc.id, target), onOpen: () => onOpenDoc(doc.id, target) } : {};
      const heading = sectionPath
        ? [
            {
              key: `s:${sectionPath}`,
              heading: true,
              ...link,
              node: (
                <>
                  <IconH1 size={11} />
                  <span className="search-line__section" title={sectionPath}>
                    {sectionLabel(sectionPath)}
                  </span>
                </>
              ),
            },
          ]
        : [];
      return heading.concat(
        fragments.map((fragment, i) => ({
          key: fragment.line == null ? `snippet:${i}` : `l${fragment.line}`,
          ...link,
          node: (
            <>
              <span className="search-line__no">{fragment.line ?? ''}</span>
              <span className="search-line__text">{highlightSubstring(fragment.text, query)}</span>
            </>
          ),
        })),
      );
    });

    return (
      <ResultGroup
        key={doc.id}
        icon={<IconDoc size={14} />}
        title={highlightSubstring(doc.title, query)}
        href={docUrl(doc.id, { find })}
        onOpen={() => onOpenDoc(doc.id, { find })}
        meta={doc.updatedAt ? new Date(doc.updatedAt).toLocaleDateString(i18n.language) : null}
        subtitle={doc.parentList?.length > 0 && <Breadcrumb parents={doc.parentList} />}
        rows={rows}
      />
    );
  });
};

export default DocResults;
