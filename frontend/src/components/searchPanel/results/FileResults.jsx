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
        href={filesUrl(file.path, project, rev)}
        onOpen={() => onOpenFile(file.path, project, { rev })}
        meta={t('files.matches', { count: file.lines.length })}
        subtitle={dir && <span className="search-group__path">{dir}</span>}
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
