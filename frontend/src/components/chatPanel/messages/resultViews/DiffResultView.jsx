import { useTranslation } from 'react-i18next';
import { DiffLines, DiffStats } from '../diffRender';
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
 * Какие файлы открыты при первом показе: подряд, пока не выбран бюджет строк.
 * Первый — всегда, иначе «Обзор» открылся бы пустым списком заголовков.
 */
const initialOpen = (groups) => {
  const open = {};
  let used = 0;
  let first = true;
  for (const group of groups) {
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

/** Шапка коммита: хеш, сообщение, автор и дата. */
const CommitHead = ({ commit }) => {
  const { i18n } = useTranslation('chat');
  const date = commit.date ? new Date(commit.date) : null;
  const shown = date && !Number.isNaN(date.getTime()) ? date.toLocaleDateString(i18n.language) : null;

  return (
    <div className="tool-diff__commit">
      <span className="tool-diff__hash">{commit.hash}</span>
      {commit.message && (
        <span className="tool-diff__message" title={commit.message}>
          {commit.message}
        </span>
      )}
      {commit.author && <span className="tool-diff__author">{commit.author}</span>}
      {shown && <span className="tool-diff__date">{shown}</span>}
    </div>
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
          {file.header && (
            <div className="tool-diff__meta">
              {file.header.map((line, i) => (
                // Индекс как key безопасен: текст патча в открытой модалке неизменен.
                // title обязателен: строка режется многоточием, прокрутки у неё
                // нет, и длинный путь иначе не прочитать.
                <span key={i} className="tool-diff__meta-line" title={line}>
                  {line}
                </span>
              ))}
            </div>
          )}
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
  const expand = useExpandAll(
    files.map((file) => file.key),
    () => initialOpen(groups),
  );

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
          {group.commit && <CommitHead commit={group.commit} />}
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
