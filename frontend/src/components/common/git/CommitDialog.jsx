import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import ModalShell from '@/components/common/modal/ModalShell';
import ChangeDiffView from '@/components/filesPanel/changes/ChangeDiffView';
import useChangeDiff from '@/components/filesPanel/changes/useChangeDiff';
import { readChangesFlat, saveChangesFlat } from '@/components/filesPanel/changes/changesLayout';
import { sortByName } from '@/components/filesPanel/changes/changeTree';
import '@/components/common/ui/buttons.css';
import CommitFileList from './CommitFileList';
import GitOutputCard from './GitOutputCard';
import useCommitSelection from './useCommitSelection';
import './commitDialog.css';

/**
 * Окно коммита: слева — что менялось и что из этого коммитить, справа — сам
 * патч выбранной строки, внизу во всю ширину — описание.
 *
 * Одно окно на обе поверхности — вкладку «Репозиторий» в чате и меню git над
 * деревом файлов: коммит там и там означает одно и то же, и разойтись им
 * нельзя. Панели остаётся собрать `git` (см. ниже) и решить, когда окно открыто.
 *
 * Контракт `git`: `status`, `project`, `refreshToken` — про какой репозиторий и
 * на каком тике спрашивать патч; `changes` — незакоммиченное, из которого
 * выбирают; `disabled` + `disabledReason` — почему команда сейчас не пойдёт;
 * `failure` — отказ последней команды, `commit(message, paths)` — сама команда.
 * `changesLoading` и `changesError` — список ещё едет либо не прочёлся: из
 * «Файлов» окно открывают и в режиме дерева, где его никто не спрашивал
 * заранее, и «изменений нет» вместо ответа было бы неправдой ровно в том окне,
 * которое открыли ради них.
 *
 * Описание — одна строка, а не многострочное поле: коммит из чата сохраняет то,
 * что наменял ассистент, и на поле в три строки уходит место, которое здесь
 * стоит отдать списку и диффу. Длинное сообщение с телом пишут в терминале, там
 * же, где правят историю.
 *
 * Окно не закрывается по отказу git — в отказе и есть весь смысл возврата сюда:
 * pre-commit hook или занятый чат оставляют набранное сообщение и выбор на
 * месте, чтобы не набирать заново.
 */
const CommitDialog = ({ git, onClose }) => {
  const { t } = useTranslation(['files', 'common']);
  const entries = git.changes ?? [];
  const selection = useCommitSelection(entries);
  const [flat, setFlat] = useState(readChangesFlat);
  const [message, setMessage] = useState('');
  const [wanted, setWanted] = useState('');

  // Открытый файл выводится из списка, а не хранится синхронно с ним: список
  // перечитывается на каждую правку ассистента, и файл под курсором может из
  // него исчезнуть (его откатили, его закоммитили). Тогда diff показывает
  // первый по порядку, а не пустоту с прежним именем в шапке.
  const sorted = sortByName(entries);
  const openPath = sorted.some((entry) => entry.path === wanted) ? wanted : sorted[0]?.path ?? '';
  const openEntry = sorted.find((entry) => entry.path === openPath) ?? null;

  const diff = useChangeDiff({
    project: git.project,
    path: openPath,
    refreshToken: git.refreshToken,
    enabled: !!openPath,
  });

  const canCommit = selection.count > 0 && !!message.trim() && !git.disabled;

  const submit = (e) => {
    e.preventDefault();
    if (!canCommit) return;
    // Успех резолвится результатом команды, отказ — undefined (см. runGitCommand):
    // закрываем только то окно, чья работа действительно записана.
    git.commit(message.trim(), selection.picked).then((result) => {
      if (result) onClose();
    });
  };

  const changeLayout = (next) => {
    setFlat(next);
    saveChangesFlat(next);
  };

  return (
    <ModalShell variant="fullscreen" onClose={onClose} className="commit-dialog">
      <form className="commit-dialog__form" onSubmit={submit}>
        <h2 className="commit-dialog__title">
          {t('git.commitDialog.title')}
          <span className="commit-dialog__branch">{git.status?.current}</span>
        </h2>

        {/* Причина «сейчас нельзя» — одна на всё окно: она общая для команды, и
            повторённая под каждой кнопкой читалась бы как несколько запретов. */}
        {git.disabled && git.disabledReason && (
          <p className="commit-dialog__blocked" role="status">
            {t(`git.blocked.${git.disabledReason}`)}
          </p>
        )}

        <div className="commit-dialog__body">
          <CommitFileList
            entries={entries}
            selection={selection}
            flat={flat}
            onLayoutChange={changeLayout}
            note={listNote(git, t)}
            openPath={openPath}
            onOpen={setWanted}
          />

          <div className="commit-dialog__diff">
            {openEntry ? (
              <>
                <div className="commit-dialog__diff-head">
                  <span className={`commit-files__status commit-files__status--${openEntry.status}`}>
                    {openEntry.status}
                  </span>
                  <span className="commit-dialog__diff-path">{openEntry.path}</span>
                </div>
                <div className="commit-dialog__diff-body">
                  <ChangeDiffView diff={diff} />
                </div>
              </>
            ) : (
              <p className="commit-dialog__empty">{listNote(git, t) ?? t('changes.empty')}</p>
            )}
          </div>
        </div>

        {/* Отказ показывается словами самого git: «Permission denied (publickey)»
            говорит человеку, что чинить, а «не удалось выполнить команду» — нет. */}
        {git.failure && (
          <GitOutputCard
            event={{
              command: git.failure.command,
              ok: false,
              output: git.failure.reason || t('git.failedUnknown'),
            }}
            compact
          />
        )}

        <div className="commit-dialog__footer">
          <input
            className="commit-dialog__message"
            value={message}
            onChange={(e) => setMessage(e.target.value)}
            placeholder={t('git.commitMessage')}
            aria-label={t('git.commitMessage')}
            maxLength={4000}
          />
          <button type="button" className="btn btn--ghost" onClick={onClose}>
            {t('git.cancel')}
          </button>
          <button type="submit" className="btn btn--primary" disabled={!canCommit}>
            {selection.count > 0
              ? t('git.commitDialog.submitCount', { count: selection.count })
              : t('git.commitDialog.submit')}
          </button>
        </div>
      </form>
    </ModalShell>
  );
};

/**
 * Что сказать вместо списка, пока его нет: ответ ещё едет либо не прочёлся.
 * Пусто — список настоящий, и говорить за него нечего.
 */
const listNote = (git, t) => {
  if (git.changesError) return t('common:loadError');
  if (git.changesLoading) return t('common:loading');
  return null;
};

export default CommitDialog;
