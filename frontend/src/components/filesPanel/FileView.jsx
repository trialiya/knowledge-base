import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import gitApi from '@/api/gitApi';
import { formatFileSize } from '@/utils/formatting';
import { previewKind, defaultPreviewView } from '@/utils/filePreview';
import ChangeDiffView from './changes/ChangeDiffView';

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
                <code>{line.length ? line : ' '}</code>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
};

/**
 * Картинка из репозитория: её грузит сам браузер по адресу сырых байт
 * (`/api/git/files/raw`), а не панель — содержимое, пришедшее с `browse`, для
 * этого не годится, там либо текст, либо ничего (бинарный файл).
 *
 * Отказ показываем сами: у `<img>` на 400/415 нет ничего, кроме сломанной
 * иконки, а причин отказа две осмысленные — файл слишком велик и файл не
 * картинка, — и обе стоят строки текста.
 */
const ImageView = ({ path, project, rev, reloadToken = 0 }) => {
  const { t } = useTranslation('files');
  const src = gitApi.rawUrl(path, { project, rev });
  const [failed, setFailed] = useState(false);
  // Сброс в рендере, а не эффектом: иначе кадр между сменой файла и эффектом
  // показал бы отказ от предыдущей картинки поверх новой. Токен обновления
  // репозитория тоже сбрасывает: откат правки или pull мог принести картинку
  // туда, где её только что не было.
  const key = `${src}\n${reloadToken}`;
  const [prevKey, setPrevKey] = useState(key);
  if (prevKey !== key) {
    setPrevKey(key);
    setFailed(false);
  }

  if (failed) return <div className="file-content__empty">{t('file.imageUnavailable')}</div>;
  return (
    <div className="file-image">
      {/* key, а не параметр в адресе: адрес картинки — это пара «проект, путь»,
          и заводить в нём поле под версию значило бы держать в ссылке то, что
          ссылкой не является. Перемонтированный <img> запрашивает байты заново,
          а закэшировать прошлый ответ было нечем — он отдан с no-store. */}
      <img key={key} className="file-image__img" src={src} alt={path} onError={() => setFailed(true)} />
    </div>
  );
};

/**
 * Один файл: строка метаданных и его содержимое — код, рисунок, разметка или
 * diff.
 *
 * `diff` — незакоммиченные изменения этого файла, если панель их показывает
 * (режим «Изменения»): `{ entry, loading, error }` либо null, когда переключать
 * не на что (обычное дерево файлов, превью в модалках). Сам выбор «оригинал или
 * diff» тоже приходит пропом: по умолчанию он зависит от открытого файла (у
 * изменённого — diff, у неотслеживаемого — содержимое), а решение, зависящее от
 * файла, живёт там, где известно, какой файл открыт.
 *
 * Выбор «рисунок или исходник», наоборот, здесь: его делают для файла, который
 * уже открыт, и переживать открытие другого он не должен — у растровой картинки
 * исходника нет вовсе.
 */
const FileView = ({
  file,
  path,
  project = '',
  rev = '',
  reloadToken = 0,
  diff = null,
  showDiff = false,
  onToggleDiff,
}) => {
  const { t } = useTranslation('files');
  const filePath = path ?? file?.path ?? '';
  const kind = previewKind(filePath);

  const [view, setView] = useState(null);
  const [prevPath, setPrevPath] = useState(filePath);
  if (prevPath !== filePath) {
    setPrevPath(filePath);
    setView(null);
  }
  const shown = view ?? defaultPreviewView(kind);
  const preview = shown === 'preview';
  // Растр переключать не на что: текста у него нет, и «исходником» была бы
  // заглушка «бинарный файл». Она же — у markdown, который не прочитался
  // текстом (UTF-16, например): расширение обещает разметку, а показать по
  // кнопке нечего.
  const togglable = (kind === 'vector' || (kind === 'markdown' && !file.binary)) && !showDiff;

  return (
    <div className="file-view">
      <div className="file-view__meta">
        {file.language && <span className="file-view__badge">{file.language}</span>}
        {!file.binary && <span>{t('file.lines', { count: file.lineCount })}</span>}
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
        {togglable && (
          <button
            type="button"
            className={`file-view__view-toggle${preview ? ' file-view__view-toggle--active' : ''}`}
            aria-pressed={preview}
            onClick={() => setView(preview ? 'source' : 'preview')}
            title={t(kind === 'vector' ? 'file.togglePicture' : 'file.toggleMarkdown')}
          >
            {preview ? '{ }' : '👁'}
          </button>
        )}
      </div>
      {showDiff ? (
        <ChangeDiffView diff={diff} />
      ) : kind === 'image' || (kind === 'vector' && preview) ? (
        <ImageView path={filePath} project={project} rev={rev} reloadToken={reloadToken} />
      ) : file.binary ? (
        <div className="file-content__empty">{t('file.binary')}</div>
      ) : kind === 'markdown' && preview ? (
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

export default FileView;
