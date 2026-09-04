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

/**
 * Окно коммита: слева — что менялось и что из этого коммитить, справа — сам
 * патч выбранной строки, внизу во всю ширину — описание.
 *
 * Единственная модалка, которая осталась у git в чате, и она ничего не
 * дублирует: выбрать файлы для коммита больше негде — панель «Файлы» показывает
 * изменения, но коммитит их только целиком.
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
  const { t } = useTranslation(['chat', 'files']);
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
          {t('commit.title')}
          <span className="commit-dialog__branch">{git.status?.current}</span>
        </h2>

        {/* Причина «сейчас нельзя» — одна на всё окно: она общая для команды, и
            повторённая под каждой кнопкой читалась бы как несколько запретов. */}
        {git.disabled && git.disabledReason && (
          <p className="commit-dialog__blocked" role="status">
            {t(`repo.blocked.${git.disabledReason}`)}
          </p>
        )}

        <div className="commit-dialog__body">
          <CommitFileList
            entries={entries}
            selection={selection}
            flat={flat}
            onLayoutChange={changeLayout}
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
              <p className="commit-dialog__empty">{t('files:changes.empty')}</p>
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
              output: git.failure.reason || t('files:git.failedUnknown'),
            }}
            compact
          />
        )}

        <div className="commit-dialog__footer">
          <input
            className="commit-dialog__message"
            value={message}
            onChange={(e) => setMessage(e.target.value)}
            placeholder={t('files:git.commitMessage')}
            aria-label={t('files:git.commitMessage')}
            maxLength={4000}
          />
          <button type="button" className="btn btn--ghost" onClick={onClose}>
            {t('files:git.cancel')}
          </button>
          <button type="submit" className="btn btn--primary" disabled={!canCommit}>
            {selection.count > 0 ? t('commit.submitCount', { count: selection.count }) : t('commit.submit')}
          </button>
        </div>
      </form>
    </ModalShell>
  );
};

export default CommitDialog;
