import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { IconFileText, IconHistory } from '@/icons/index';
import { filesUrl } from '@/navigation/urlScheme';
import { highlightSubstring } from '@/components/common/search/highlightMatch';
import ResultGroup from './ResultGroup';
import LineOrigin from './LineOrigin';
import '@/components/common/ui/gitChrome.css';

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
 * Файл, найденный ещё и по имени (`nameMatch`), подписан так же; найденный
 * только по имени — без строк, с пометкой «по имени» вместо счётчика.
 *
 * Подсветка ищется по самому запросу и только когда он — обычная строка: под
 * регулярным выражением совпал не он, а то, что оно описывает, и красить по
 * тексту шаблона значило бы врать. Бэкенд позиции не отдаёт (git grep их не
 * печатает), поэтому подстрока ищется здесь, без учёта регистра — так же, как
 * искал git.
 */
const FileResults = ({ result, query, regex, rev, project, onOpenFile }) =>
  result.files.map((file) => (
    <FileCard
      // Открытые «когда появилось» — ответы на этот поиск: под новым запросом,
      // ревизией или режимом та же строка значит другое, и панель, оставшаяся
      // открытой, сама спросила бы снова то, о чём не просили. Новый ключ — новая
      // карточка, все панели закрыты.
      key={JSON.stringify([project ?? '', rev ?? '', !!regex, query, file.path])}
      file={file}
      query={query}
      regex={regex}
      rev={rev}
      project={project}
      onOpenFile={onOpenFile}
    />
  ));

/**
 * Карточка файла. У строки совпадения — «когда появилось»: коммит, где
 * подстрока вошла в эту строку (GET /api/git/files/origin), ответ — под
 * строкой. Только для обычной подстроки: под регулярным выражением «подстроки»
 * нет, совпало то, что шаблон описывает. И только у файла в git — у
 * неотслеживаемого истории нет.
 */
const FileCard = ({ file, query, regex, rev, project, onOpenFile }) => {
  const { t } = useTranslation('search');
  // Открытые панели — по номеру строки; открыть можно несколько и сравнить.
  const [open, setOpen] = useState(() => new Set());
  const toggle = (line) =>
    setOpen((prev) => {
      const next = new Set(prev);
      if (!next.delete(line)) next.add(line);
      return next;
    });
  const traceable = !regex && file.tracked !== false;
  const { dir, name } = splitPath(file.path);

  return (
    <ResultGroup
      icon={<IconFileText size={14} />}
      title={regex ? name : highlightSubstring(name, query)}
      href={filesUrl(file.path, project, { rev, find: query, findRegex: regex })}
      onOpen={() => onOpenFile(file.path, project, { rev, find: query, findRegex: regex })}
      meta={file.lines.length > 0 ? t('files.matches', { count: file.lines.length }) : t('files.byName')}
      subtitle={
        (dir || file.tracked === false || file.nameMatch) && (
          <>
            {dir && <span className="search-group__path">{dir}</span>}
            {file.nameMatch && file.lines.length > 0 && (
              <span className="git-untracked-badge">{t('files.nameMatch')}</span>
            )}
            {file.tracked === false && <span className="git-untracked-badge">{t('files.untracked')}</span>}
          </>
        )
      }
      rows={file.lines.map((line) => ({
        key: line.line,
        node: (
          <>
            <span className="search-line__no">{line.line}</span>
            <code className="search-line__code">{regex ? line.text : highlightSubstring(line.text, query)}</code>
            {traceable && (
              <button
                type="button"
                className="icon-btn icon-btn--xs icon-btn--quiet search-line__origin"
                aria-expanded={open.has(line.line)}
                aria-label={t('files.origin.ask')}
                title={t('files.origin.ask')}
                onClick={() => toggle(line.line)}
              >
                <IconHistory size={12} />
              </button>
            )}
          </>
        ),
        detail: open.has(line.line) && (
          <LineOrigin
            path={file.path}
            line={line.line}
            query={query}
            rev={rev}
            project={project}
            onOpenFile={onOpenFile}
          />
        ),
      }))}
    />
  );
};

export default FileResults;
