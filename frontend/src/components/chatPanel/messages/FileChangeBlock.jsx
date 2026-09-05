import { useState, useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { getFileChangeRefs } from './toolMeta';
import { TOOL_STATUS } from '@/constants/toolStatus';
import { IconChevronDown, IconUndo } from '@/icons/index';
import ConfirmModal from '@/components/common/modal/ConfirmModal';
import FileDiffModal from './FileDiffModal';
import { DiffStats } from './diffRender';
import chatApi from '@/api/chatApi';
import '@/components/common/ui/buttons.css';
import '../styles/doc-changes.css';
import '../styles/file-changes.css';

/**
 * Блок под ответом ИИ: файловые мутации (createFile/editFile/runScript) из toolCalls.
 * Строка на файл: путь, операция, +N/−M; клик открывает модалку со всеми
 * diff'ами правок этого файла из данного ответа (diff приходит в resultMeta —
 * работает и в live-стриме, и после перезагрузки чата, как у DocChangeBlock).
 *
 * У последнего ответа каждая строка ещё и откатывается — своей кнопкой справа: решение о каждом
 * файле человек принимает отдельно, посмотрев его diff. `canRevert` (см. MessageList) говорит,
 * что этот блок — последний и чат свободен, `revertedPaths` — какие файлы прежние откаты уже
 * вернули: у них вместо кнопки отметка. Откатывает сервер, по своей записи в истории
 * (см. ChatFileRevert) — включая репозиторий, — поэтому кнопке нечего передавать, кроме чата и
 * пути; новый ряд истории и сброс кэшей файлов приезжают событием FILE_REVERT.
 */
const FileChangeBlock = ({ toolCalls, project, conversationId, canRevert = false, revertedPaths }) => {
  const { t } = useTranslation('chat');
  const [target, setTarget] = useState(null); // { path, operation, additions, deletions, diffs } | null
  const [open, setOpen] = useState(false);
  const [confirming, setConfirming] = useState(null); // change | null
  // Отказ сервера показываем текстом под списком: «файл изменился после ответа» — это и есть
  // ответ пользователю, а не техническая деталь.
  const [failure, setFailure] = useState(null); // текст | null
  const [reverting, setReverting] = useState(null); // path | null

  // Одна строка на файл: суммарные +/− по всем успешным правкам, diff'ы копятся
  // в порядке выполнения. Упавшие вызовы (ERROR) пропускаются — они файл не меняли.
  const changes = useMemo(() => {
    const byPath = new Map();
    for (const tc of toolCalls || []) {
      // Один вызов может принести несколько правок: runScript пишет пачкой.
      for (const ref of getFileChangeRefs(tc)) {
        if (ref.status === TOOL_STATUS.ERROR) continue;
        const cur = byPath.get(ref.path);
        if (!cur) {
          byPath.set(ref.path, { ...ref, diffs: ref.diff ? [ref.diff] : [] });
        } else {
          cur.additions += ref.additions;
          cur.deletions += ref.deletions;
          if (ref.operation === 'create') cur.operation = 'create';
          if (ref.diff) cur.diffs.push(ref.diff);
        }
      }
    }
    return [...byPath.values()];
  }, [toolCalls]);

  // Правки скрипта откат не отменяет — и не по файлу, а весь ответ (см. FileRevertPlan): кнопок
  // у такого блока нет вовсе, иначе они обещали бы то, в чём сервер откажет.
  const scripted = useMemo(
    () =>
      (toolCalls || []).some(
        (tc) => tc.name === 'runScript' && tc.status !== TOOL_STATUS.ERROR && tc.resultMeta?.edits?.length > 0,
      ),
    [toolCalls],
  );
  const revertable = canRevert && !scripted;

  const revert = async () => {
    const { path } = confirming;
    setConfirming(null);
    setReverting(path);
    setFailure(null);
    try {
      await chatApi.revertFiles(conversationId, [path]);
    } catch (e) {
      // Отказ сервера сам называет файл; путь дописывается только к нашему общему тексту.
      setFailure(e?.reason || `${path}: ${t('fileChange.revertFailed')}`);
    } finally {
      setReverting(null);
    }
  };

  if (changes.length === 0) return null;

  return (
    <div className="doc-change-block">
      <button type="button" className="change-block-summary" onClick={() => setOpen((v) => !v)} aria-expanded={open}>
        <span className="change-block-summary-icon" aria-hidden="true">
          📝
        </span>
        <span className="change-block-summary-text">
          {t('fileChange.summary', { count: changes.length, defaultValue: `Files changed (${changes.length})` })}
        </span>
        <span className={`change-block-chevron ${open ? 'change-block-chevron--open' : ''}`}>
          <IconChevronDown />
        </span>
      </button>

      {open &&
        changes.map((c) => {
          const reverted = revertedPaths?.has(c.path) ?? false;
          return (
            <div key={c.path} className={`file-change-row ${reverted ? 'file-change-row--reverted' : ''}`}>
              <button
                type="button"
                className="doc-change-item"
                onClick={() => setTarget(c)}
                title={t('fileChange.viewChanges')}
              >
                <span className="doc-change-icon" aria-hidden="true">
                  {c.operation === 'create' ? '🆕' : '✏️'}
                </span>
                <span className="doc-change-text">
                  <span className="doc-change-title">{c.path}</span>
                  <span className="doc-change-sub">
                    {c.operation === 'create' ? t('fileChange.created') : t('fileChange.edited')}
                    {' · '}
                    <DiffStats additions={c.additions} deletions={c.deletions} />
                  </span>
                </span>
                <span className="doc-change-cta">{t('fileChange.viewChanges')} ›</span>
              </button>
              {revertable && reverted && (
                <span className="file-change-row__reverted" title={t('fileChange.revertedFile')}>
                  <IconUndo />
                </span>
              )}
              {revertable && !reverted && (
                <button
                  type="button"
                  className="icon-btn"
                  onClick={() => setConfirming(c)}
                  disabled={reverting != null}
                  title={reverting === c.path ? t('fileChange.reverting') : t('fileChange.revertFile')}
                  aria-label={t('fileChange.revertFile')}
                >
                  <IconUndo />
                </button>
              )}
            </div>
          );
        })}

      {open && failure && <div className="file-change-revert__error">{failure}</div>}

      {target && <FileDiffModal change={target} project={project} onClose={() => setTarget(null)} />}
      <ConfirmModal
        open={confirming != null}
        title={t('fileChange.revertTitle')}
        message={t(
          confirming?.operation === 'create' ? 'fileChange.revertMessageCreated' : 'fileChange.revertMessage',
          {
            path: confirming?.path,
          },
        )}
        confirmLabel={t('fileChange.revert')}
        onConfirm={revert}
        onCancel={() => setConfirming(null)}
      />
    </div>
  );
};

export default FileChangeBlock;
