import { useTranslation } from 'react-i18next';
import { IconCommit } from '@/icons/index';
import { commitUrl } from '@/navigation/urlScheme';
import { navigateToCommit } from '@/navigation/fileNavigationBus';
import { highlightSubstring } from '@/components/common/search/highlightMatch';
import ResultGroup from './ResultGroup';
import RelativeTime from '@/components/common/ui/RelativeTime';

/**
 * Совпадения в истории: карточка на коммит — заголовок сообщения, под ним хеш и
 * автор, внутри — строки описания, где встретился запрос (номер — строка тела).
 *
 * Карточка ведёт туда же, куда любая ссылка на коммит (urlScheme.commitUrl):
 * снимок в «Файлах» с изменёнными файлами и вкладкой «Коммит», где описание
 * видно целиком. Ревизия фильтра в адрес не уходит — коммит сам себе ревизия.
 *
 * Коммит, найденный по префиксу хеша (`hashMatch`), строк не несёт и помечен,
 * а хеш подсвечен: иначе карточка без единого совпадения читалась бы как ошибка.
 */
const CommitResults = ({ result, query, project }) => {
  const { t } = useTranslation('search');

  return result.commits.map((commit) => (
    <ResultGroup
      key={commit.hash}
      icon={<IconCommit size={14} />}
      title={highlightSubstring(commit.message, query)}
      href={commitUrl(commit.hash, project)}
      onOpen={() => navigateToCommit(commit.hash, project)}
      meta={commit.date && <RelativeTime value={commit.date} />}
      subtitle={
        <>
          <code className="search-group__hash">
            {commit.hashMatch ? highlightSubstring(commit.shortHash, query) : commit.shortHash}
          </code>
          <span className="search-group__author">{commit.author}</span>
          {commit.hashMatch && <span className="search-group__badge">{t('commits.hashMatched')}</span>}
        </>
      }
      rows={commit.lines.map((line) => ({
        key: line.line,
        node: (
          <>
            <span className="search-line__no">{line.line}</span>
            <span className="search-line__text">{highlightSubstring(line.text, query)}</span>
          </>
        ),
      }))}
    />
  ));
};

export default CommitResults;
