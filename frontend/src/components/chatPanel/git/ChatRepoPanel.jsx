import { useTranslation } from 'react-i18next';
import { IconBranch, IconTerminal } from '@/icons/index';
import '@/components/common/ui/buttons.css';

/**
 * Вкладка «Репозиторий» правой панели чата: где мы и что не сохранено.
 *
 * Вкладка отвечает, модалка делает. Кнопок команд здесь нет ни одной, кроме
 * одной двери — «Команды…»: git-команду запускают раз в сеанс, и панель,
 * постоянно занятая под то, что делают редко, стоит дороже лишнего клика.
 * По той же причине здесь нет ни журнала, ни вывода: последняя команда — одна
 * строка, а вывод целиком лежит в ленте чата, где команда оставила свой ряд.
 */
const ChatRepoPanel = ({ git, onOpenCommands }) => {
  const { t } = useTranslation(['chat', 'files']);

  // Пусто только пока ответа не было ни разу: перезапрос после команды держит
  // прежнее состояние на экране, иначе вкладка мигала бы на каждую команду.
  if (git.loading && !git.status) return null;
  if (!git.status) return <p className="chat-empty-note">{t('repo.unavailable')}</p>;

  const { current, detached, unborn, upstream, ahead, behind, merging, conflicts } = git.status;
  const allowed = !!git.capabilities?.commands;

  return (
    <div className="chat-repo">
      <div
        className="chat-repo__branch"
        title={
          detached
            ? t('files:git.detachedHint')
            : upstream
            ? t('files:git.upstream', { upstream })
            : t('files:git.noUpstream')
        }
      >
        <IconBranch size={13} />
        <span className="chat-repo__branch-name">{current}</span>
        {detached && <span className="chat-repo__tag">{t('files:git.detached')}</span>}
        {unborn && <span className="chat-repo__tag">{t('files:git.unborn')}</span>}
        {/* Ноль не показываем: строка отвечает «разошлись ли», а два нуля рядом
            с веткой читают на каждом открытии панели и ничего из них не узнают. */}
        {behind > 0 && (
          <span className="chat-repo__count" title={t('files:git.behindHint', { count: behind })}>
            ↓{behind}
          </span>
        )}
        {ahead > 0 && (
          <span className="chat-repo__count" title={t('files:git.aheadHint', { count: ahead })}>
            ↑{ahead}
          </span>
        )}
      </div>

      {/* Незавершённый merge меняет смысл всего остального: в файлах лежат
          маркеры конфликта, и обычные команды не пройдут. Пока он не закрыт,
          об этом говорится прямо — и это единственное, что вкладка показывает
          сверх ветки без просьбы. */}
      {merging && (
        <div className="chat-repo__merge" role="status">
          {conflicts?.length ? t('files:git.mergeConflicts', { count: conflicts.length }) : t('files:git.merging')}
        </div>
      )}

      {allowed && (
        <button
          type="button"
          className="btn btn--primary chat-repo__commands"
          onClick={onOpenCommands}
          disabled={git.disabled}
          title={git.disabledReason ? t(`repo.blocked.${git.disabledReason}`) : undefined}
        >
          <IconTerminal size={14} />
          {t('repo.commands')}
        </button>
      )}

      {git.changes.length > 0 && (
        <section className="chat-repo__section">
          <h3 className="chat-repo__section-title">{t('repo.uncommitted', { count: git.changes.length })}</h3>
          <ul className="chat-repo__files">
            {git.changes.map((entry) => {
              const name = entry.path.split('/').pop();
              const dir = entry.path.slice(0, -name.length - 1);
              return (
                // Буква статуса — те же обозначения, что печатает `git status`:
                // подписать их словом в панели шириной 320px негде, и слово
                // живёт в подсказке строки, как и в панели «Файлы».
                <li
                  key={entry.path}
                  className="chat-repo__file"
                  title={`${t(`repo.fileStatus.${entry.status}`, entry.status)} · ${entry.path}`}
                >
                  <span className={`chat-repo__status chat-repo__status--${entry.status}`}>{entry.status}</span>
                  {/* Имя раньше каталога, хотя в пути порядок обратный: сжимать
                      в такой строке приходится именно каталог — обрезанное имя
                      файла не опознать вовсе. */}
                  <span className="chat-repo__name">{name}</span>
                  {dir && <span className="chat-repo__dir">{dir}</span>}
                </li>
              );
            })}
          </ul>
        </section>
      )}

      {git.last && (
        <section className="chat-repo__section">
          <h3 className="chat-repo__section-title">{t('repo.lastCommand')}</h3>
          <p className={`chat-repo__last${git.last.ok ? '' : ' chat-repo__last--failed'}`}>
            <span className="chat-repo__last-command">{git.last.command}</span>
            <span className="chat-repo__last-outcome">
              {git.last.ok ? t('repo.outcomeOk') : t('repo.outcomeFailed')}
            </span>
          </p>
        </section>
      )}
    </div>
  );
};

export default ChatRepoPanel;
