import { useTranslation } from 'react-i18next';
import { IconBranch, IconRefreshCw } from '@/icons/index';
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
const GitBranchBar = ({ status, capabilities, running, onFetch }) => {
  const { t } = useTranslation('files');

  // Ещё не ответили или ответили отказом — строки нет вовсе. Пустая строка на
  // месте ветки заняла бы высоту и сказала меньше, чем её отсутствие.
  if (!status) return null;

  const { current, detached, unborn, upstream, ahead, behind } = status;

  return (
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

      {capabilities?.commands && (
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
      )}
    </div>
  );
};

export default GitBranchBar;
