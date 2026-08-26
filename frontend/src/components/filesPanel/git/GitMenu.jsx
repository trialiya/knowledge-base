import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { useTranslation } from 'react-i18next';
import { IconDots, IconCheck } from '@/icons/index';
import useDismissable from '@/components/common/layout/useDismissable';
import './gitMenu.css';

/** Отступ от края окна, за который список не заезжает. */
const VIEWPORT_GAP = 12;
/** Потолок ширины: дальше растёт не читаемость, а расстояние до курсора. */
const MAX_WIDTH = 420;

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
 * ветка ничего не отслеживает).
 *
 * Список уходит порталом и позиционируется по кнопке — тот же приём, что у
 * `PanelSearch` в этой же панели: у `.workspace__side` стоит `overflow: hidden`,
 * и вложенный список обрезало бы её границей. Имя ветки длиннее панели — дело
 * обычное, так что обрезало бы ровно то, ради чего список и открывают.
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
  // Якорь и открытость — одно состояние: рисовать список без координат негде, а
  // координаты без списка ничего не значат.
  const [anchor, setAnchor] = useState(null);
  const open = !!anchor;
  const triggerRef = useRef(null);
  const portalRef = useRef(null);
  const close = useCallback(() => setAnchor(null), []);

  // Клик внутри списка — не «клик снаружи», хотя порталом тот уехал из триггера.
  const dismissRefs = useMemo(() => [triggerRef, portalRef], []);
  useDismissable(open, dismissRefs, close);

  // Якорь снят в момент открытия и за окном не следит: пересчитывать позицию на
  // каждый scroll и resize дороже, чем закрыть меню, — как в useSearchDropdown.
  // Прокрутки внутри самого списка (веток бывает много) это не касается.
  useEffect(() => {
    if (!open) return undefined;
    const onScroll = (e) => {
      if (e.target instanceof Element && portalRef.current?.contains(e.target)) return;
      close();
    };
    window.addEventListener('resize', close);
    window.addEventListener('scroll', onScroll, true);
    return () => {
      window.removeEventListener('resize', close);
      window.removeEventListener('scroll', onScroll, true);
    };
  }, [open, close]);

  const toggle = () => setAnchor(open ? null : triggerRef.current?.getBoundingClientRect() ?? null);

  const act = (fn) => () => {
    close();
    fn();
  };

  const branches = status?.branches ?? [];
  const dirty = !!status?.dirty;
  // Втягивать имеет смысл всегда, когда ветка что-то отслеживает: счётчик
  // «позади» показывает данные последнего fetch'а и может отставать от remote.
  const tracking = !!status?.upstream;

  // Раскрывается вправо, поверх центра: слева от кнопки только ширина панели, а
  // имена веток бывают вдвое длиннее неё. Потолок обязателен — список меряется
  // по самому длинному пункту, и без потолка `text-overflow` у пункта не
  // сработает никогда: ужиматься тому нечем.
  const dropdownStyle = anchor && {
    top: anchor.bottom + 6,
    left: anchor.left,
    maxWidth: Math.min(MAX_WIDTH, window.innerWidth - anchor.left - VIEWPORT_GAP),
  };

  return (
    <div className="git-menu" ref={triggerRef}>
      <button
        type="button"
        className="icon-btn"
        aria-haspopup="menu"
        aria-expanded={open}
        disabled={running}
        title={t('git.menu')}
        aria-label={t('git.menu')}
        onClick={toggle}
      >
        <IconDots size={14} />
      </button>

      {open &&
        createPortal(
          <div className="git-menu__dropdown" role="menu" ref={portalRef} style={dropdownStyle}>
            {(branches.length > 1 || status?.detached) && (
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
                      // Обрезанные имена расходятся не только началом:
                      // `feature/…-check-4` и `feature/…-check-30` читаются
                      // одинаково, и без полного имени выбор ветки становится
                      // угадыванием.
                      title={branch}
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
          </div>,
          document.body,
        )}
    </div>
  );
};

export default GitMenu;
