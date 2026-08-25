import { useTranslation } from 'react-i18next';
import { IconBranch, IconRefreshCw } from '@/icons/index';
import GitMenu from './GitMenu';
import './gitBranchBar.css';

/**
 * Строка состояния репозитория над деревом: на какой ветке панель, насколько
 * ветка разошлась с upstream и кнопка обновить remote-refs.
 *
 * Ветка показывается всегда — это часть ответа на вопрос «что я сейчас вижу», —
 * а кнопки только там, где проект разрешил команды пользователя
 * (`kb.projects[].git-commands`). Счётчики читаются по refs на диске, поэтому
 * `fetch` стоит именно здесь: без него «позади» никогда не изменится.
 */
const GitBranchBar = ({ status, capabilities, running, onFetch, onAbortMerge, commands }) => {
  const { t } = useTranslation('files');

  // Ещё не ответили или ответили отказом — строки нет вовсе. Пустая строка на
  // месте ветки заняла бы высоту и сказала меньше, чем её отсутствие.
  if (!status) return null;

  const { current, detached, unborn, upstream, ahead, behind, merging, conflicts } = status;
  const allowed = !!capabilities?.commands;

  return (
    <>
      {/* Незавершённый merge меняет смысл всего, что панель показывает ниже:
          в файлах лежат маркеры конфликта, а обычные операции не пройдут. Пока
          он не закрыт, об этом говорится прямо, вместе с гарантированным
          выходом — abort. */}
      {merging && (
        <div className="git-merge" role="status">
          <span className="git-merge__text">
            {conflicts?.length ? t('git.mergeConflicts', { count: conflicts.length }) : t('git.merging')}
          </span>
          {allowed && (
            <button type="button" className="btn btn--ghost btn--sm" disabled={running} onClick={onAbortMerge}>
              {t('git.abortMerge')}
            </button>
          )}
        </div>
      )}
      <div className="git-branch">
        <span
          className="git-branch__name"
          title={detached ? t('git.detachedHint') : upstream ? t('git.upstream', { upstream }) : t('git.noUpstream')}
        >
          <IconBranch size={13} />
          <span className="git-branch__label">{current}</span>
          {detached && <span className="git-branch__tag">{t('git.detached')}</span>}
          {unborn && <span className="git-branch__tag">{t('git.unborn')}</span>}
        </span>

        {/* Ноль не показываем: строка отвечает «разошлись ли», а два нуля рядом с
          веткой — это шум, который читают на каждом открытии панели. */}
        {behind > 0 && (
          <span className="git-branch__count" title={t('git.behindHint', { count: behind })}>
            ↓{behind}
          </span>
        )}
        {ahead > 0 && (
          <span className="git-branch__count" title={t('git.aheadHint', { count: ahead })}>
            ↑{ahead}
          </span>
        )}

        {allowed && (
          <>
            <button
              type="button"
              className="icon-btn git-branch__action"
              onClick={onFetch}
              disabled={running}
              title={t('git.fetch')}
              aria-label={t('git.fetch')}
            >
              <IconRefreshCw size={13} spinning={running} />
            </button>
            <GitMenu status={status} running={running} {...commands} />
          </>
        )}
      </div>
    </>
  );
};

export default GitBranchBar;
