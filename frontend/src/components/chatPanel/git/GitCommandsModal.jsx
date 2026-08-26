import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import ModalShell from '@/components/common/modal/ModalShell';
import GitOutputCard from './GitOutputCard';
import './gitCommandsModal.css';

/**
 * Все git-команды чата в одном месте, сгруппированные по тому, что они трогают:
 * обмен с origin, рабочее дерево, ветка.
 *
 * Недоступная команда не прячется, а объясняется. Кнопка, исчезающая из-под
 * курсора, когда дерево стало чистым, учит только тому, что интерфейсу нельзя
 * доверять; подпись «коммитить нечего» отвечает на вопрос, ради которого модалку
 * и открыли.
 *
 * Модалка не закрывается по результату команды: после pull с конфликтом
 * следующий шаг — прервать merge — делают отсюда же, и закрыть её значило бы
 * отправить человека открывать её заново в худший для этого момент.
 */
const GitCommandsModal = ({ git, onClose }) => {
  const { t } = useTranslation(['chat', 'files']);
  const [message, setMessage] = useState('');
  const [branch, setBranch] = useState('');

  const status = git.status;
  if (!status) return null;

  const { current, detached, unborn, upstream, ahead, behind, dirty, merging, branches } = status;
  const off = git.disabled;
  const canPush = !!git.capabilities?.push;

  const commit = () => {
    const text = message.trim();
    if (!text) return;
    git.commit(text).then(() => setMessage(''));
  };

  return (
    <ModalShell onClose={onClose} className="git-commands">
      <h2 className="git-commands__title">
        {t('repo.title')}
        <span className="git-commands__branch">{current}</span>
      </h2>

      {/* Одна причина «сейчас нельзя» на всю модалку, а не подпись под каждой из
          девяти кнопок: причина общая, и повторённая девять раз она читается
          как девять разных запретов. */}
      {off && (
        <p className="git-commands__blocked" role="status">
          {t('repo.busyHint')}
        </p>
      )}

      <section className="git-commands__group">
        <h3 className="git-commands__group-title">{t('repo.groupRemote')}</h3>
        <div className="git-commands__row">
          <button type="button" className="btn btn--ghost" disabled={off} onClick={git.fetch}>
            {t('repo.fetch')}
          </button>
          <button
            type="button"
            className="btn btn--ghost"
            disabled={off || !upstream || unborn}
            onClick={git.pull}
          >
            {t('repo.pull')}
          </button>
          <button
            type="button"
            className="btn btn--ghost"
            disabled={off || !canPush || unborn || detached}
            onClick={git.push}
          >
            {t('repo.push')}
          </button>
        </div>
        <p className="git-commands__note">
          {!upstream
            ? t('files:git.noUpstream')
            : behind > 0 || ahead > 0
              ? t('repo.diverged', { ahead, behind })
              : t('repo.inSync', { upstream })}
          {!canPush && ` · ${t('repo.pushNotAllowed')}`}
        </p>
      </section>

      <section className="git-commands__group">
        <h3 className="git-commands__group-title">
          {dirty ? t('repo.groupTreeDirty') : t('repo.groupTreeClean')}
        </h3>
        <textarea
          className="git-commands__input"
          value={message}
          onChange={(e) => setMessage(e.target.value)}
          placeholder={t('files:git.commitMessage')}
          rows={2}
          disabled={off || !dirty}
        />
        <div className="git-commands__row">
          <button
            type="button"
            className="btn btn--primary"
            disabled={off || !dirty || !message.trim() || detached}
            onClick={commit}
          >
            {t('files:git.commit')}
          </button>
          <button type="button" className="btn btn--ghost" disabled={off || !dirty} onClick={git.stashPush}>
            {t('repo.stash')}
          </button>
          <button type="button" className="btn btn--ghost" disabled={off} onClick={git.stashPop}>
            {t('repo.stashPop')}
          </button>
        </div>
        <p className="git-commands__note">
          {detached
            ? t('files:git.detachedHint')
            : dirty
              ? t('files:git.commitHint')
              : t('files:git.nothingToCommit')}
        </p>
      </section>

      <section className="git-commands__group">
        <h3 className="git-commands__group-title">{t('files:git.branch')}</h3>
        <div className="git-commands__row">
          <select
            className="git-commands__select"
            value={branch}
            onChange={(e) => setBranch(e.target.value)}
            disabled={off || unborn}
            aria-label={t('files:git.switchTo')}
          >
            <option value="">{t('repo.pickBranch')}</option>
            {branches
              .filter((name) => name !== current)
              .map((name) => (
                <option key={name} value={name}>
                  {name}
                </option>
              ))}
          </select>
          <button
            type="button"
            className="btn btn--ghost"
            disabled={off || !branch}
            onClick={() => git.switchBranch(branch).then(() => setBranch(''))}
          >
            {t('repo.switch')}
          </button>
        </div>
      </section>

      {merging && (
        <section className="git-commands__group git-commands__group--merge">
          <h3 className="git-commands__group-title">{t('files:git.merging')}</h3>
          <button type="button" className="btn btn--danger" disabled={off} onClick={git.abortMerge}>
            {t('files:git.abortMerge')}
          </button>
          <p className="git-commands__note">{t('repo.conflictsInFiles')}</p>
        </section>
      )}

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

      <div className="git-commands__footer">
        <button type="button" className="btn btn--ghost" onClick={onClose}>
          {t('repo.close')}
        </button>
      </div>
    </ModalShell>
  );
};

export default GitCommandsModal;
