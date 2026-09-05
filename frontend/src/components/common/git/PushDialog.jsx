import { useTranslation } from 'react-i18next';
import ModalShell from '@/components/common/modal/ModalShell';
import { formatDateTime } from '@/utils/formatting';
import '@/components/common/ui/buttons.css';
import GitOutputCard from './GitOutputCard';
import useOutgoingCommits from './useOutgoingCommits';
import './pushDialog.css';

/**
 * Окно push: что именно уедет из деплоя наружу.
 *
 * Push — единственная команда, отправляющая содержимое репозитория за пределы
 * деплоя (потому у проекта под неё и отдельное разрешение), и единственная,
 * которую нельзя отменить своими силами. Список коммитов перед ней — не
 * украшение: по нему видно, что вместе с работой ассистента не уезжает чужой
 * коммит, случайно оказавшийся на ветке.
 *
 * Список читается по refs на диске: он настолько свеж, насколько свеж последний
 * fetch, — как и счётчик «↑», из которого он и раскрывается.
 *
 * Окно общее с панелью «Файлы»; контракт `git` — тот же, что у окна коммита,
 * командой здесь служит `push()`.
 */
const PushDialog = ({ git, onClose }) => {
  const { t, i18n } = useTranslation(['files', 'common']);
  const outgoing = useOutgoingCommits({
    project: git.project,
    refreshToken: git.refreshToken,
    enabled: true,
  });

  const upstream = git.status?.upstream ?? null;
  const commits = outgoing.commits;
  // Пустой список — «нечего отправлять» только когда его действительно прочли:
  // упавший запрос ничего не знает про ветку, и гасить по нему push значило бы
  // запретить отправку из-за сбоя чтения.
  const nothingToPush = !outgoing.loading && !outgoing.error && commits.length === 0;

  return (
    <ModalShell variant="wide" onClose={onClose} className="push-dialog">
      <h2 className="push-dialog__title">
        {t('git.pushDialog.title')}
        <span className="push-dialog__branch">{git.status?.current}</span>
      </h2>

      <p className="push-dialog__target">
        {upstream
          ? t('git.upstream', { upstream })
          : /* Ветка ещё ничего не отслеживает: push проставит upstream сам, если
               remote у репозитория ровно один, — и опубликует всю её работу. */
            t('git.pushDialog.newBranch')}
      </p>

      {git.disabled && git.disabledReason && (
        <p className="push-dialog__blocked" role="status">
          {t(`git.blocked.${git.disabledReason}`)}
        </p>
      )}

      <h3 className="push-dialog__section">{t('git.pushDialog.commits', { count: commits.length })}</h3>
      {outgoing.loading ? (
        <p className="push-dialog__note">{t('common:loading')}</p>
      ) : outgoing.error ? (
        <p className="push-dialog__note">{t('git.pushDialog.loadError')}</p>
      ) : nothingToPush ? (
        <p className="push-dialog__note">{t('git.nothingToPush')}</p>
      ) : (
        <ul className="push-dialog__commits">
          {commits.map((commit) => (
            <li key={commit.hash} className="push-dialog__commit" title={commit.message}>
              <span className="push-dialog__hash">{commit.shortHash}</span>
              <span className="push-dialog__message">{commit.message}</span>
              <span className="push-dialog__author">{commit.author}</span>
              <span className="push-dialog__date">{formatDateTime(commit.date, i18n.language)}</span>
            </li>
          ))}
        </ul>
      )}

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

      <div className="push-dialog__footer">
        <button type="button" className="btn btn--ghost" onClick={onClose}>
          {t('git.cancel')}
        </button>
        <button
          type="button"
          className="btn btn--primary"
          disabled={git.disabled || nothingToPush}
          // Окно не закрывается по отказу: причину («Permission denied
          // (publickey)», «rejected — non-fast-forward») читают здесь же.
          onClick={() => git.push().then((result) => result && onClose())}
        >
          {t('git.pushDialog.submit')}
        </button>
      </div>
    </ModalShell>
  );
};

export default PushDialog;
