import { useTranslation } from 'react-i18next';
import { IconFileText } from '@/icons/index';
import { filesUrl } from '@/navigation/urlScheme';
import { highlightSubstring } from '@/components/common/search/highlightMatch';
import ResultGroup from './ResultGroup';

/** Имя и каталог пути: имя несёт заголовок карточки, каталог — строку под ним. */
function splitPath(path) {
  const at = path.lastIndexOf('/');
  return at < 0 ? { dir: '', name: path } : { dir: path.slice(0, at), name: path.slice(at + 1) };
}

/**
 * Совпадения в файлах репозитория: карточка на файл, внутри — строки с номерами.
 *
 * Неотслеживаемый файл (нашёлся только с галочкой «искать в untracked», в зоне
 * `allow-globs` проекта) подписан как таковой: истории у него нет, и строка
 * могла прийти из отчёта сборки, а не из исходника, — без подписи такая
 * карточка неотличима от находки в коде.
 *
 * Подсветка ищется по самому запросу и только когда он — обычная строка: под
 * регулярным выражением совпал не он, а то, что оно описывает, и красить по
 * тексту шаблона значило бы врать. Бэкенд позиции не отдаёт (git grep их не
 * печатает), поэтому подстрока ищется здесь, без учёта регистра — так же, как
 * искал git.
 */
const FileResults = ({ result, query, regex, rev, project, onOpenFile }) => {
  const { t } = useTranslation('search');

  return result.files.map((file) => {
    const { dir, name } = splitPath(file.path);
    return (
      <ResultGroup
        key={file.path}
        icon={<IconFileText size={14} />}
        title={name}
        href={filesUrl(file.path, project, { rev, find: query, findRegex: regex })}
        onOpen={() => onOpenFile(file.path, project, { rev, find: query, findRegex: regex })}
        meta={t('files.matches', { count: file.lines.length })}
        subtitle={
          (dir || file.tracked === false) && (
            <>
              {dir && <span className="search-group__path">{dir}</span>}
              {file.tracked === false && (
                <span className="search-group__badge search-group__badge--untracked">{t('files.untracked')}</span>
              )}
            </>
          )
        }
        rows={file.lines.map((line) => ({
          key: line.line,
          node: (
            <>
              <span className="search-line__no">{line.line}</span>
              <code className="search-line__code">{regex ? line.text : highlightSubstring(line.text, query)}</code>
            </>
          ),
        }))}
      />
    );
  });
};

export default FileResults;
