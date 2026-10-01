import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import gitApi from '@/api/gitApi';
import { formatFileSize } from '@/utils/formatting';
import { previewKind, defaultPreviewView } from '@/utils/filePreview';
import { parseLines } from '@/navigation/urlScheme';
import ChangeDiffView from './changes/ChangeDiffView';
import CodeView from './code/CodeView';
import useFileBlame from './code/useFileBlame';

/**
 * Заголовок разметки с номером своей строки в исходнике (`data-line`) — по нему
 * вкладка «Структура» прокручивает к символу: номер строки бэкенд отдаёт, а
 * DOM-заголовок по нему иначе не найти.
 */
const withLine = (Tag) =>
  function HeadingWithLine({ node, ...props }) {
    return <Tag data-line={node?.position?.start.line} {...props} />;
  };
const HEADINGS_WITH_LINES = Object.fromEntries(['h1', 'h2', 'h3', 'h4', 'h5', 'h6'].map((tag) => [tag, withLine(tag)]));

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
 *
 * `blame` / `onToggleBlame` — колонка авторства строк: включена ли она (из
 * адреса, `?blame=1`) и чем её переключать. Без `onToggleBlame` (превью в
 * модалках) тумблера нет и колонка не спрашивается.
 *
 * `lines` — выделенные строки из адреса (`?lines=42-45`): они подсвечены в
 * исходнике, и файл один раз прокручивается к ним, когда содержимое пришло.
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
  blame = false,
  onToggleBlame = null,
  lines = '',
  jump = null,
}) => {
  const { t } = useTranslation('files');
  const filePath = path ?? file?.path ?? '';
  const rootRef = useRef(null);
  const kind = previewKind(filePath);

  const [view, setView] = useState(null);
  const [prevPath, setPrevPath] = useState(filePath);
  if (prevPath !== filePath) {
    setPrevPath(filePath);
    setView(null);
  }
  const marked = parseLines(lines);
  // Выделение есть только у строк исходника: SVG, открытый ради строк, — текстом.
  const shown = view ?? (marked ? 'source' : defaultPreviewView(kind));
  const preview = shown === 'preview';
  // Переключать есть что, только когда у файла два вида. У растра исходника нет
  // вовсе, а у SVG и markdown, не прочитавшихся текстом (UTF-16, встроенный
  // NUL), он есть только по расширению: показать по кнопке всё равно нечего,
  // кроме заглушки «бинарный файл». Сам рисунок при этом остаётся — SVG в
  // UTF-16 браузер рисует, читать его текстом отказались мы.
  const togglable = (kind === 'vector' || kind === 'markdown') && !file.binary && !showDiff;
  // Усечённый большой файл — голова и хвост без середины: номера строк после
  // разрыва в разметке не совпадают с исходником, и прокрутка по ним увела бы
  // не туда. У такого файла строки номеров не несут, и к символу не едем.
  const excerpt = file.truncated && file.fromLine == null;
  // Авторство есть только у строк с историей: не у бинарного, не у усечённого
  // (номера его строк не настоящие), не у неотслеживаемого, не у diff'а, и
  // только в исходнике — у разметки в превью строк нет.
  const blamable =
    !!onToggleBlame &&
    !file.binary &&
    !excerpt &&
    file.tracked !== false &&
    !showDiff &&
    !(kind === 'markdown' && preview);
  const blameShown = blamable && blame;
  const lineBlame = useFileBlame({ path: filePath, project, rev, reloadToken, enabled: blameShown });

  // Прокрутка к символу структуры: и в разметке, и в исходнике строка помечена
  // `data-line`. Эффект следует объекту `jump`, а не строке — повторный клик по
  // тому же символу возвращает к нему.
  useEffect(() => {
    if (!jump) return;
    rootRef.current?.querySelector(`[data-line="${jump.line}"]`)?.scrollIntoView({ block: 'start' });
  }, [jump]);

  // К выделенным строкам — один раз на файл и диапазон, а не на каждый приход
  // содержимого: обновление репозитория перечитывает файл, и прокрутка не
  // должна уводить оттуда, куда пользователь уже ушёл сам. Файл без выделения
  // память сбрасывает: «Назад» на ссылку с `?lines=` обязан привести к строкам снова.
  const scrolledTo = useRef('');
  const markKey = marked ? `${filePath}\n${lines}` : '';
  useEffect(() => {
    if (!markKey) scrolledTo.current = '';
    if (!markKey || scrolledTo.current === markKey) return;
    const row = rootRef.current?.querySelector(`[data-line="${marked.from}"]`);
    if (!row) return;
    scrolledTo.current = markKey;
    row.scrollIntoView({ block: 'center' });
  }, [markKey, marked, file]);

  return (
    <div className="file-view" ref={rootRef}>
      <div className="file-view__meta">
        {file.language && <span className="file-view__badge">{file.language}</span>}
        {!file.binary && <span>{t('file.lines', { count: file.lineCount })}</span>}
        <span>{formatFileSize(file.sizeBytes)}</span>
        {file.truncated && <span className="file-view__badge file-view__badge--warn">{t('file.truncated')}</span>}
        {diff && (
          <button
            type="button"
            className="btn btn--ghost btn--sm file-view__toggle"
            aria-pressed={showDiff}
            onClick={() => onToggleDiff(!showDiff)}
          >
            {t('file.showDiff')}
          </button>
        )}
        {blamable && (
          <button
            type="button"
            className="btn btn--ghost btn--sm file-view__toggle"
            aria-pressed={blameShown}
            onClick={() => onToggleBlame(!blameShown)}
            title={t('file.blameTitle')}
          >
            {t('file.showBlame')}
          </button>
        )}
        {blameShown && lineBlame.error && (
          <span className="file-view__badge file-view__badge--warn">{t('file.blameError')}</span>
        )}
        {togglable && (
          <button
            type="button"
            className="btn btn--ghost btn--sm file-view__toggle"
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
          <ReactMarkdown remarkPlugins={[remarkGfm]} components={excerpt ? undefined : HEADINGS_WITH_LINES}>
            {file.content ?? ''}
          </ReactMarkdown>
        </div>
      ) : (
        // truncated + fromLine == null — это head+tail-вырезка большого файла
        // (см. GitService.headTailExcerpt): хвост идёт не сразу за головой,
        // сквозная нумерация от 1 была бы неверной для его строк. Диапазонный
        // же запрос (fromLine задан) нумеруется корректно от fromLine.
        <CodeView
          text={file.content ?? ''}
          fromLine={file.fromLine ?? 1}
          showLineNumbers={!excerpt}
          blame={blameShown ? lineBlame : null}
          marked={excerpt ? null : marked}
          path={filePath}
          project={project}
        />
      )}
    </div>
  );
};

export default FileView;
