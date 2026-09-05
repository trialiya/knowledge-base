import { useTranslation } from 'react-i18next';
import { IconBranch, IconCheck, IconChevronRight, IconFolder, IconUpload } from '@/icons/index';
import { sortByName } from '@/components/filesPanel/changes/changeTree';
import { navigateToFile } from '@/navigation/fileNavigationBus';
import '@/components/common/ui/buttons.css';
import '@/components/common/ui/gitChrome.css';

/**
 * Вкладка «Репозиторий» правой панели чата: где мы, что ассистент наменял и как
 * это сохранить.
 *
 * Из чата спрашивают ровно эти три вещи, и вкладка отвечает только на них.
 * Ветки, stash, pull, откат файла и выход из merge живут в панели «Файлы», где
 * они сделаны целиком; второе место, повторяющее первое, обязано с ним
 * разойтись, поэтому здесь его нет — вместо него ссылка.
 *
 * Список показывает первые {@link VISIBLE_CHANGES} файлов, а не весь: правка на
 * пол-репозитория — обычный ответ ассистента, и панель шириной 320px, в которой
 * полсотни строк, отвечает на вопрос «что наменяли» хуже, чем счётчик и четыре
 * имени. Весь список — в окне коммита и в «Файлах», куда и ведёт ссылка.
 */
const VISIBLE_CHANGES = 4;

const ChatRepoPanel = ({ git, onOpenCommit, onOpenPush }) => {
  const { t } = useTranslation(['chat', 'files']);

  // Пусто только пока ответа не было ни разу: перезапрос после команды держит
  // прежнее состояние на экране, иначе вкладка мигала бы на каждую команду.
  if (git.loading && !git.status) return null;
  if (!git.status) return <p className="chat-empty-note">{t('repo.unavailable')}</p>;

  const { current, detached, unborn, upstream, ahead, behind, merging, conflicts } = git.status;
  // По имени файла, а не в порядке бэкенда: показываются первые несколько, и
  // «первые» обязаны быть предсказуемыми — иначе на каждое обновление в панели
  // оказывается другая четвёрка.
  const changes = sortByName(git.changes ?? []);
  const hidden = Math.max(0, changes.length - VISIBLE_CHANGES);
  // Отправлять нечего — это про ветку, а не про права: разрешение проекта решает,
  // быть ли кнопке вообще.
  const nothingToPush = !!upstream && ahead === 0;

  const openChanges = () => navigateToFile('', git.project, { changes: true });
  // Одна подпись на обе кнопки: причина «сейчас нельзя» у них общая — работает
  // модель, идёт команда, чата ещё нет, — и повторённая словами она читалась бы
  // как два разных запрета.
  const blocked = git.disabled && git.disabledReason ? t(`files:git.blocked.${git.disabledReason}`) : undefined;

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
          <span className="git-count" title={t('files:git.behindHint', { count: behind })}>
            ↓{behind}
          </span>
        )}
        {ahead > 0 && (
          <span className="git-count" title={t('files:git.aheadHint', { count: ahead })}>
            ↑{ahead}
          </span>
        )}
      </div>

      {/* Незавершённый merge меняет смысл всего остального: в файлах лежат
          маркеры конфликта, и коммит не пройдёт. Кнопки выхода здесь нет —
          конфликт правят там же, где его видно построчно. */}
      {merging && (
        <div className="git-merge-note chat-repo__merge" role="status">
          {conflicts?.length ? t('files:git.mergeConflicts', { count: conflicts.length }) : t('files:git.merging')}
        </div>
      )}

      <section className="chat-repo__section">
        <div className="chat-repo__section-head">
          <h3 className="chat-repo__section-title">{t('repo.uncommitted', { count: changes.length })}</h3>
          {changes.length > 0 && (
            <button type="button" className="chat-repo__link" onClick={openChanges}>
              {t('repo.allChanges')}
              <IconChevronRight size={12} />
            </button>
          )}
        </div>

        {changes.length === 0 ? (
          <p className="chat-repo__note">{t('files:changes.empty')}</p>
        ) : (
          <>
            <ul className="chat-repo__files">
              {changes.slice(0, VISIBLE_CHANGES).map((entry) => {
                const name = entry.path.split('/').pop();
                const dir = entry.path.slice(0, -name.length - 1);
                return (
                  // Буква статуса — те же обозначения, что печатает `git status`:
                  // подписать их словом в панели шириной 320px негде, и слово
                  // живёт в подсказке строки, как и в панели «Файлы».
                  <li key={entry.path} className="chat-repo__file">
                    <button
                      type="button"
                      className="chat-repo__file-btn"
                      title={`${t(`repo.fileStatus.${entry.status}`, entry.status)} · ${entry.path}`}
                      onClick={() => navigateToFile(entry.path, git.project, { changes: true })}
                    >
                      <span className={`chat-repo__status chat-repo__status--${entry.status}`}>{entry.status}</span>
                      {/* Имя раньше каталога, хотя в пути порядок обратный: сжимать
                          в такой строке приходится именно каталог — обрезанное имя
                          файла не опознать вовсе. */}
                      <span className="chat-repo__name">{name}</span>
                      {dir && <span className="chat-repo__dir">{dir}</span>}
                    </button>
                  </li>
                );
              })}
            </ul>
            {hidden > 0 && (
              <button type="button" className="chat-repo__link chat-repo__link--muted" onClick={openChanges}>
                {t('repo.moreChanges', { count: hidden })}
                <IconChevronRight size={12} />
              </button>
            )}
          </>
        )}
      </section>

      <section className="chat-repo__section">
        <h3 className="chat-repo__section-title">{t('repo.saveWork')}</h3>
        {/* Кнопки друг под другом, а не в ряд: это два разных по цене действия —
            коммит остаётся здесь, push отправляет содержимое за пределы деплоя, —
            и ряд уравнял бы их в правах. */}
        <button
          type="button"
          className="btn btn--primary chat-repo__action"
          disabled={git.disabled || changes.length === 0}
          title={changes.length === 0 ? t('files:git.nothingToCommit') : blocked}
          onClick={onOpenCommit}
        >
          <IconCheck size={14} />
          {t('repo.commit')}
        </button>
        {git.capabilities?.push && (
          <button
            type="button"
            className="btn btn--ghost chat-repo__action"
            disabled={git.disabled || nothingToPush}
            title={nothingToPush ? t('files:git.nothingToPush') : blocked}
            onClick={onOpenPush}
          >
            <IconUpload size={14} />
            {t('repo.push')}
          </button>
        )}
      </section>

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

      <button type="button" className="chat-repo__link chat-repo__link--muted chat-repo__door" onClick={openChanges}>
        <IconFolder size={13} />
        {t('repo.restInFiles')}
        <IconChevronRight size={12} />
      </button>
    </div>
  );
};

export default ChatRepoPanel;
