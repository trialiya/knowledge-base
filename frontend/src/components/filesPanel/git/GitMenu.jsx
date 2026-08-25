import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { IconDots, IconCheck } from '@/icons/index';
import './gitMenu.css';

/**
 * Меню git-операций рядом с веткой: переключиться на другую ветку, создать
 * новую, спрятать изменения в stash и вернуть их, закоммитить.
 *
 * Ветки и команды в одном меню, а не в двух: пользователь приходит сюда с одним
 * вопросом — «что сделать с репозиторием», — и разделение по признаку «это
 * список, а это действия» ему ничего не объясняет.
 *
 * Пункты, которым сейчас нечего делать, показываются выключенными, а не
 * прячутся: исчезающий пункт заставляет гадать, был он вообще или нет, тогда как
 * выключенный вместе с подписью отвечает, чего не хватает (нечего коммитить,
 * stash пуст).
 */
const GitMenu = ({
  status,
  capabilities,
  running,
  onSwitch,
  onCreateBranch,
  onStashPush,
  onStashPop,
  onCommit,
  onPull,
  onPush,
}) => {
  const { t } = useTranslation('files');
  const [open, setOpen] = useState(false);
  const ref = useRef(null);

  useEffect(() => {
    if (!open) return undefined;
    const onDocClick = (e) => {
      if (ref.current && !ref.current.contains(e.target)) setOpen(false);
    };
    const onKey = (e) => {
      if (e.key === 'Escape') setOpen(false);
    };
    document.addEventListener('mousedown', onDocClick);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onDocClick);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);

  const act = (fn) => () => {
    setOpen(false);
    fn();
  };

  const branches = status?.branches ?? [];
  const dirty = !!status?.dirty;
  // Втягивать имеет смысл всегда, когда ветка что-то отслеживает: счётчик
  // «позади» показывает данные последнего fetch'а и может отставать от remote.
  const tracking = !!status?.upstream;

  return (
    <div className="git-menu" ref={ref}>
      <button
        type="button"
        className="icon-btn"
        aria-haspopup="menu"
        aria-expanded={open}
        disabled={running}
        title={t('git.menu')}
        aria-label={t('git.menu')}
        onClick={() => setOpen((o) => !o)}
      >
        <IconDots size={14} />
      </button>

      {open && (
        <div className="git-menu__dropdown" role="menu">
          {branches.length > 1 && (
            <>
              <div className="git-menu__section">{t('git.switchTo')}</div>
              {branches.map((branch) => {
                const current = branch === status.current;
                return (
                  <button
                    key={branch}
                    type="button"
                    role="menuitem"
                    className="git-menu__item"
                    disabled={current}
                    onClick={act(() => onSwitch(branch))}
                  >
                    <span className="git-menu__mark">{current && <IconCheck size={12} />}</span>
                    <span className="git-menu__label">{branch}</span>
                  </button>
                );
              })}
              <div className="git-menu__sep" />
            </>
          )}

          <button type="button" role="menuitem" className="git-menu__item" onClick={act(onCreateBranch)}>
            <span className="git-menu__mark" />
            <span className="git-menu__label">{t('git.newBranch')}</span>
          </button>
          <div className="git-menu__sep" />

          <button
            type="button"
            role="menuitem"
            className="git-menu__item"
            disabled={!tracking}
            title={tracking ? t('git.pullHint') : t('git.noUpstream')}
            onClick={act(onPull)}
          >
            <span className="git-menu__mark" />
            <span className="git-menu__label">{t('git.pull')}</span>
          </button>
          {/* push — отдельное разрешение проекта: единственная команда, которая
              отправляет содержимое репозитория за пределы деплоя. */}
          {capabilities?.push && (
            <button
              type="button"
              role="menuitem"
              className="git-menu__item"
              disabled={status?.ahead === 0 && tracking}
              title={status?.ahead === 0 && tracking ? t('git.nothingToPush') : undefined}
              onClick={act(onPush)}
            >
              <span className="git-menu__mark" />
              <span className="git-menu__label">{t('git.push')}</span>
            </button>
          )}
          <div className="git-menu__sep" />

          <button
            type="button"
            role="menuitem"
            className="git-menu__item"
            disabled={!dirty}
            title={dirty ? undefined : t('git.nothingToStash')}
            onClick={act(onStashPush)}
          >
            <span className="git-menu__mark" />
            <span className="git-menu__label">{t('git.stash')}</span>
          </button>
          <button type="button" role="menuitem" className="git-menu__item" onClick={act(onStashPop)}>
            <span className="git-menu__mark" />
            <span className="git-menu__label">{t('git.stashPop')}</span>
          </button>
          <button
            type="button"
            role="menuitem"
            className="git-menu__item"
            disabled={!dirty}
            title={dirty ? undefined : t('git.nothingToCommit')}
            onClick={act(onCommit)}
          >
            <span className="git-menu__mark" />
            <span className="git-menu__label">{t('git.commit')}</span>
          </button>
        </div>
      )}
    </div>
  );
};

export default GitMenu;
