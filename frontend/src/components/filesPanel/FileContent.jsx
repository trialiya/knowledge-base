import { useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { IconFolder, IconDoc } from '@/icons/index';
import { formatFileSize } from '@/utils/formatting';
import FindBar from '@/components/common/search/FindBar';
import useAddressFind from '@/components/common/search/useAddressFind';
import Breadcrumb from './Breadcrumb';
import ChangeDiffView from './changes/ChangeDiffView';

const isMarkdownPath = (path) => /\.mdx?$/i.test(path || '');

const CodeView = ({ text, fromLine = 1, showLineNumbers = true }) => {
  const lines = text.split('\n');
  return (
    <div className="file-code">
      <table className="file-code__table">
        <tbody>
          {lines.map((line, i) => (
            <tr key={i}>
              {showLineNumbers && <td className="file-code__gutter">{fromLine + i}</td>}
              <td className="file-code__line">
                <code>{line.length ? line : ' '}</code>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
};

const DirectoryListing = ({ nodes, onNavigate }) => {
  const { t } = useTranslation('files');
  if (nodes.length === 0) {
    return <div className="file-content__empty">{t('tree.empty')}</div>;
  }
  return (
    <div className="file-listing">
      {nodes.map((node) => (
        <button key={node.path} className="file-listing__row" onClick={() => onNavigate(node.path)}>
          <span className={`file-listing__icon ${node.type === 'directory' ? 'file-listing__icon--folder' : ''}`}>
            {node.type === 'directory' ? <IconFolder /> : <IconDoc />}
          </span>
          <span className="file-listing__name">{node.name}</span>
          {node.type === 'file' && node.size != null && (
            <span className="file-listing__size">{formatFileSize(node.size)}</span>
          )}
        </button>
      ))}
    </div>
  );
};

/**
 * `diff` — незакоммиченные изменения этого файла, если панель их показывает
 * (режим «Изменения»): `{ entry, loading, error }` либо null, когда переключать
 * не на что (обычное дерево файлов, превью в модалках). Сам выбор «оригинал или
 * diff» тоже приходит пропом: по умолчанию он зависит от открытого файла (у
 * изменённого — diff, у неотслеживаемого — содержимое), а решение, зависящее от
 * файла, живёт там, где известно, какой файл открыт.
 */
export const FileView = ({ file, path, diff = null, showDiff = false, onToggleDiff }) => {
  const { t } = useTranslation('files');
  const isMd = isMarkdownPath(path ?? file?.path);
  const [mdView, setMdView] = useState(false);
  return (
    <div className="file-view">
      <div className="file-view__meta">
        {file.language && <span className="file-view__badge">{file.language}</span>}
        <span>{t('file.lines', { count: file.lineCount })}</span>
        <span>{formatFileSize(file.sizeBytes)}</span>
        {file.truncated && <span className="file-view__badge file-view__badge--warn">{t('file.truncated')}</span>}
        {diff && (
          <button
            type="button"
            className="btn btn--ghost btn--sm file-view__diff-toggle"
            aria-pressed={showDiff}
            onClick={() => onToggleDiff(!showDiff)}
          >
            {t('file.showDiff')}
          </button>
        )}
        {isMd && !file.binary && !showDiff && (
          <button
            type="button"
            className={`file-view__md-toggle${mdView ? ' file-view__md-toggle--active' : ''}`}
            onClick={() => setMdView((v) => !v)}
            title={t('file.toggleMarkdown', { defaultValue: 'Markdown preview' })}
          >
            {mdView ? '{ }' : '👁'}
          </button>
        )}
      </div>
      {showDiff ? (
        <ChangeDiffView diff={diff} />
      ) : file.binary ? (
        <div className="file-content__empty">{t('file.binary')}</div>
      ) : mdView ? (
        <div className="file-view__md">
          <ReactMarkdown remarkPlugins={[remarkGfm]}>{file.content ?? ''}</ReactMarkdown>
        </div>
      ) : (
        // truncated + fromLine == null — это head+tail-вырезка большого файла
        // (см. GitService.headTailExcerpt): хвост идёт не сразу за головой,
        // сквозная нумерация от 1 была бы неверной для его строк. Диапазонный
        // же запрос (fromLine задан) нумеруется корректно от fromLine.
        <CodeView
          text={file.content ?? ''}
          fromLine={file.fromLine ?? 1}
          showLineNumbers={!(file.truncated && file.fromLine == null)}
        />
      )}
    </div>
  );
};

/**
 * `find` (и `findRegex`) — что подсветить в открытом файле, из адреса; менять
 * его обратно в адрес — дело `onFindChange`. Пусто — файл открыли не из поиска:
 * бара нет, пока его не позовут Ctrl+F. Всё остальное — в useAddressFind.
 */
const FileContent = ({
  content,
  path,
  loading,
  onNavigate,
  diff = null,
  showDiff = false,
  onToggleDiff,
  find = '',
  findRegex = false,
  onFindChange = null,
}) => {
  const { t } = useTranslation('files');
  const bodyRef = useRef(null);
  const search = useAddressFind({ rootRef: bodyRef, find, regex: findRegex, onCommit: onFindChange });

  // Крошки рисуем по запрошенному пути, а не по загруженному содержимому: путь
  // известен сразу из URL, и шапка центра появляется, не дожидаясь ответа
  // (иначе при переходе она ещё показывала бы предыдущий файл).
  const crumbPath = path ?? content?.path ?? '';

  // Файла нет в рабочем дереве, и browse честно отвечает «не найдено» — но у
  // изменения он есть (удалён, либо переименован и открыт под старым именем), и
  // показать надо именно diff. Переключать тут не на что, поэтому тумблера в
  // этой ветке нет. Отказ запроса за патчем тоже сюда: про него расскажет сам
  // ChangeDiffView, а «файл не найден» было бы неправдой.
  const gone = content?.type === 'not-found' && !!diff && (!!diff.entry || !!diff.error);

  return (
    <div className="file-content">
      <Breadcrumb path={crumbPath} onNavigate={onNavigate} />
      {/* Бар стоит НАД телом, а не внутри него: липкая полоса внутри прокрутки
          закрывала бы верхнее совпадение, к которому сама же и промотала. */}
      {search.open && (
        <FindBar
          className="find-bar--file"
          placeholder={t('find.placeholder')}
          inputRef={search.inputRef}
          query={search.query}
          onQueryChange={search.onQueryChange}
          onCommit={search.onCommitQuery}
          total={search.total}
          activeIndex={search.activeIndex}
          note={content?.file?.truncated ? t('find.truncated') : null}
          onPrev={search.goPrev}
          onNext={search.goNext}
          onClose={search.close}
        />
      )}
      <div className="file-content__body" ref={bodyRef}>
        {loading && <div className="file-content__empty">{t('loading')}</div>}
        {!loading && content?.type === 'directory' && (
          <DirectoryListing nodes={content.nodes} onNavigate={onNavigate} />
        )}
        {/* Файл без содержимого — снимок ревизии с блобом, который больше предела
            чтения из истории. Тип пути известен, дерево на месте, показать нечего
            только здесь. */}
        {!loading && content?.type === 'file' && !content.file && (
          <div className="file-content__empty">{t('file.contentUnavailable')}</div>
        )}
        {!loading && content?.type === 'file' && !!content.file && (
          <FileView
            file={content.file}
            path={content.path}
            diff={diff}
            showDiff={showDiff}
            onToggleDiff={onToggleDiff}
          />
        )}
        {!loading && gone && (
          <div className="file-view">
            <div className="file-view__meta">
              {diff.entry && (
                <span className="file-view__badge file-view__badge--warn">
                  {t(`changes.status.${diff.entry.status}`, { defaultValue: diff.entry.status })}
                </span>
              )}
            </div>
            <ChangeDiffView diff={diff} />
          </div>
        )}
        {!loading && content?.type === 'not-found' && !gone && (
          <div className="file-content__empty">{t('file.notFound')}</div>
        )}
        {!loading && content?.type === 'error' && <div className="file-content__empty">{t('file.loadError')}</div>}
      </div>
    </div>
  );
};

export default FileContent;
