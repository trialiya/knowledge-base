import React from 'react';
import { useTranslation } from 'react-i18next';
import RightPanel from './RightPanel';
import { IconPanelLeft } from '../../icons';
import './workspaceLayout.css';

/**
 * Единая оболочка рабочей области для всех разделов (чат, база знаний, файлы).
 *
 * Раскладка одна на всё приложение: скрываемая левая панель (список/дерево),
 * основная область и правая панель, свёрнутая по умолчанию. Раньше каждый
 * раздел рисовал собственный контейнер и собственный сплит — отсюда три набора
 * почти одинаковых классов и разное поведение; здесь это одна реализация.
 *
 * Компонент УПРАВЛЯЕМЫЙ: состояние панелей живёт в URL (useAppNavigation), сюда
 * приходит пропсами. Так раскладку можно передать ссылкой, а «Назад» не тратится
 * на сворачивание панели (навигация пишет её через replaceState).
 *
 * props:
 *   left  — { title, action, toolbar, children, bodyScroll } — левая панель.
 *           bodyScroll=false отдаёт прокрутку самому содержимому: так дерево
 *           файлов сохраняет свой двухосевой скролл (строки шире панели).
 *   center — узел основной области
 *   right — [{ key, label, icon, badge, content }] — вкладки правой панели.
 *           Пустой массив/undefined → правой панели и её рельса нет вовсе.
 *   leftCollapsed / onToggleLeft — состояние и тумблер левой панели
 *   rightTab / onRightTabChange  — раскрытая вкладка справа (null — свёрнута)
 *   className — модификатор корня для специфики раздела
 */
const WorkspaceLayout = ({
  left,
  center,
  right,
  leftCollapsed = false,
  onToggleLeft,
  rightTab = null,
  onRightTabChange,
  className = '',
}) => {
  const { t } = useTranslation();
  const tabs = right || [];
  // Вкладка из URL могла устареть (раздел сменился, вкладку убрали) — тогда
  // считаем панель свёрнутой, а не падаем на пустом содержимом.
  const activeTab = tabs.find((tab) => tab.key === rightTab) || null;

  return (
    <div className={`workspace${className ? ` ${className}` : ''}`}>
      {leftCollapsed ? (
        <div className="workspace__rail workspace__rail--left">
          <button
            type="button"
            className="icon-btn workspace__rail-btn"
            onClick={onToggleLeft}
            title={t('panels.expandLeft')}
            aria-label={t('panels.expandLeft')}
            aria-expanded={false}
          >
            <IconPanelLeft size={17} />
          </button>
        </div>
      ) : (
        <aside className="workspace__side workspace__side--left" aria-label={left?.title}>
          <div className="workspace__side-head">
            <span className="workspace__side-title">{left?.title}</span>
            <button
              type="button"
              className="icon-btn"
              onClick={onToggleLeft}
              title={t('panels.collapseLeft')}
              aria-label={t('panels.collapseLeft')}
              aria-expanded
            >
              <IconPanelLeft size={17} />
            </button>
          </div>
          {left?.action && <div className="workspace__side-action">{left.action}</div>}
          {left?.toolbar && <div className="workspace__side-toolbar">{left.toolbar}</div>}
          <div className={`workspace__side-body${left?.bodyScroll === false ? ' workspace__side-body--plain' : ''}`}>
            {left?.children}
          </div>
        </aside>
      )}

      <section className="workspace__center">{center}</section>

      {tabs.length > 0 &&
        (activeTab ? (
          <RightPanel
            tabs={tabs}
            activeKey={activeTab.key}
            onTabChange={onRightTabChange}
            onClose={() => onRightTabChange(null)}
          />
        ) : (
          <div className="workspace__rail workspace__rail--right">
            {tabs.map((tab) => (
              <button
                key={tab.key}
                type="button"
                className="icon-btn workspace__rail-btn"
                onClick={() => onRightTabChange(tab.key)}
                title={tab.label}
                aria-label={tab.label}
                aria-expanded={false}
              >
                {tab.icon}
                {tab.badge > 0 && <span className="workspace__rail-badge">{tab.badge}</span>}
              </button>
            ))}
          </div>
        ))}
    </div>
  );
};

export default WorkspaceLayout;
