import { useTranslation } from 'react-i18next';
import { DiffLines, DiffStats, PatchHeader } from '../diffRender';
import { IconChevronDown } from '@/icons/index';
import ResultSummary, { useExpandAll } from './resultSummary';

// Режим «Обзор» для формы «unified diff»: коммит → файлы → раскрашенный патч,
// вместо 40 КБ JSON, в которых переносы строк экранированы как \n.
//
// Разбор ответа — в diffResult.js; сюда приходят уже готовые группы.

// Сколько строк патчей раскрыто сразу. Порог, а не «первые N файлов»: один
// большой патч и десяток мелких требуют разного, а считать за пользователя,
// сколько кликов ему не жалко, всё равно не выйдет.
const OPEN_LINE_BUDGET = 400;

// Шапка патча идёт в счёт наравне с ним: она вынесена из блока кода, но место
// на экране занимает тем же числом строк.
const lineCount = (file) => (file.patch ? file.patch.split('\n').length : 0) + (file.header?.length ?? 0);

/**
 * Ключ раскрытия для тела сообщения коммита: оно сворачивается наравне с
 * патчами, поэтому и живёт в том же состоянии — иначе «свернуть всё» оставляло
 * бы половину вида раскрытой.
 */
const bodyKey = (group) => `${group.key}-body`;

/** Всё сворачиваемое вида: патч на файл плюс тело сообщения у коммитов с ним. */
const expandKeys = (groups) => [
  ...groups.flatMap((group) => group.files.map((file) => file.key)),
  ...groups.filter((group) => group.commit?.body).map(bodyKey),
];

/**
 * Что открыто при первом показе: тела сообщений — всегда (в них и стоит
 * объяснение правки), файлы — подряд, пока не выбран бюджет строк. Первый файл
 * тоже всегда, иначе «Обзор» открылся бы пустым списком заголовков.
 */
const initialOpen = (groups) => {
  const open = {};
  let used = 0;
  let first = true;
  for (const group of groups) {
    if (group.commit?.body) open[bodyKey(group)] = true;
    for (const file of group.files) {
      const lines = lineCount(file);
      if (first || used + lines <= OPEN_LINE_BUDGET) {
        open[file.key] = true;
        used += lines;
      }
      first = false;
    }
  }
  return open;
};

/**
 * Шапка коммита: строка «хеш · тема · автор · дата», под ней — тело сообщения.
 *
 * С телом строка становится кнопкой и сворачивает его; без тела шеврона нет
 * вовсе — он обещал бы содержимое, которого у этого коммита не будет (списку
 * коммитов тела не приходят).
 */
const CommitHead = ({ commit, open, onToggle }) => {
  const { t, i18n } = useTranslation('chat');
  const date = commit.date ? new Date(commit.date) : null;
  const shown = date && !Number.isNaN(date.getTime()) ? date.toLocaleDateString(i18n.language) : null;

  const head = (
    <>
      <span className="tool-diff__hash">{commit.hash}</span>
      {commit.message && (
        <span className="tool-diff__message" title={commit.message}>
          {commit.message}
        </span>
      )}
      {commit.author && <span className="tool-diff__author">{commit.author}</span>}
      {shown && <span className="tool-diff__date">{shown}</span>}
    </>
  );

  if (!commit.body) return <div className="tool-diff__commit">{head}</div>;

  return (
    <>
      <button
        type="button"
        className="tool-diff__commit tool-diff__commit--toggle"
        onClick={onToggle}
        aria-expanded={open}
        title={t('toolCall.detail.diff.commitBody')}
      >
        <span className={`tool-diff__chevron${open ? ' tool-diff__chevron--open' : ''}`} aria-hidden="true">
          <IconChevronDown />
        </span>
        {head}
      </button>
      {/* Тема в строке шапки обрезается по ширине, поэтому тело — отдельным
          блоком: в нём и стоит объяснение правки, ради которого коммит открыли. */}
      {open && <div className="tool-diff__body">{commit.body}</div>}
    </>
  );
};

/** Файл: строка-заголовок со статусом и счётчиками, под ней — патч. */
const FileEntry = ({ file, open, onToggle }) => {
  const { t } = useTranslation('chat');
  const status = t(`toolCall.detail.diff.status.${file.status}`, { defaultValue: file.status });

  return (
    <div className="tool-diff__file">
      <button type="button" className="tool-diff__file-head" onClick={onToggle} aria-expanded={open}>
        <span className={`tool-diff__chevron${open ? ' tool-diff__chevron--open' : ''}`} aria-hidden="true">
          <IconChevronDown />
        </span>
        <span className={`tool-diff__status tool-diff__status--${file.status}`} title={status}>
          {file.status}
        </span>
        <span className="tool-diff__path" title={file.oldPath ? `${file.oldPath} → ${file.path}` : file.path}>
          {file.oldPath && <span className="tool-diff__old-path">{file.oldPath} → </span>}
          {file.path}
        </span>
        <DiffStats additions={file.additions} deletions={file.deletions} />
      </button>

      {open && (
        <>
          {/* Шапка патча — метаданные файла, поэтому она снаружи блока кода. */}
          <PatchHeader lines={file.header} />
          {file.patch ? (
            <pre className="tool-diff__patch">
              <DiffLines patch={file.patch} lineNumbers />
            </pre>
          ) : (
            <div className="tool-diff__note">{t('toolCall.detail.diff.noPatch')}</div>
          )}
        </>
      )}
    </div>
  );
};

const DiffResultView = ({ data: groups }) => {
  const { t } = useTranslation('chat');
  const files = groups.flatMap((group) => group.files);
  const expand = useExpandAll(expandKeys(groups), () => initialOpen(groups));

  const additions = files.reduce((sum, file) => sum + file.additions, 0);
  const deletions = files.reduce((sum, file) => sum + file.deletions, 0);

  return (
    <div className="tool-diff">
      {/* На один файл итог дословно повторил бы строку под ним — не считаем. */}
      {files.length > 1 && (
        <ResultSummary expand={expand}>
          {t('toolCall.detail.diff.files', { count: files.length })}
          {' · '}
          <DiffStats additions={additions} deletions={deletions} />
        </ResultSummary>
      )}

      {groups.map((group) => (
        <section key={group.key} className="tool-diff__group">
          {group.commit && (
            <CommitHead
              commit={group.commit}
              open={expand.isOpen(bodyKey(group))}
              onToggle={() => expand.toggle(bodyKey(group))}
            />
          )}
          {group.files.map((file) => (
            <FileEntry
              key={file.key}
              file={file}
              open={expand.isOpen(file.key)}
              onToggle={() => expand.toggle(file.key)}
            />
          ))}
        </section>
      ))}
    </div>
  );
};

export default DiffResultView;
