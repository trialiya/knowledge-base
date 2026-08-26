import { useTranslation } from 'react-i18next';
import RightPanel from './RightPanel';
import useLeftPanelWidth, { MIN_LEFT_WIDTH, MAX_LEFT_WIDTH } from './useLeftPanelWidth';
import { IconPanelLeft } from '@/icons/index';
import './workspaceLayout.css';
import './sidePanel.css';

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
 * Исключение — ширина левой панели: она не в пропсах и не в адресе, а в общем
 * сторе (useLeftPanelWidth). Ширина одна на все разделы и правится
 * перетаскиванием границы, поэтому её нельзя держать ни в состоянии одного
 * экземпляра (их несколько смонтировано разом), ни в ссылке.
 *
 * props:
 *   left  — { title, ariaLabel, action, toolbar, children, bodyScroll } — левая панель.
 *           bodyScroll=false отдаёт прокрутку самому содержимому: так дерево
 *           файлов сохраняет свой двухосевой скролл (строки шире панели).
 *           title может быть узлом, а не строкой (в «Файлах» заголовок — селектор
 *           репозитория); тогда имя панели для скринридера берётся из ariaLabel.
 *   center — узел основной области
 *   right — [{ key, label, icon, badge, alert, content }] — вкладки правой панели.
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
  const leftWidth = useLeftPanelWidth();
  const tabs = right || [];
  // Вкладка из URL могла устареть (раздел сменился, вкладку убрали) — тогда
  // считаем панель свёрнутой, а не падаем на пустом содержимом.
  const activeTab = tabs.find((tab) => tab.key === rightTab) || null;

  return (
    <div className={`workspace${className ? ` ${className}` : ''}${leftWidth.dragging ? ' workspace--resizing' : ''}`}>
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
        <aside
          className="workspace__side workspace__side--left"
          aria-label={typeof left?.title === 'string' ? left.title : left?.ariaLabel}
        >
          <div className="workspace__side-head">
            <span
              className={`workspace__side-title${
                typeof left?.title === 'string' ? '' : ' workspace__side-title--slot'
              }`}
            >
              {left?.title}
            </span>
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

      {/* Граница левой панели: тянется мышью, стрелками с клавиатуры, двойной
          клик возвращает ширину по умолчанию. У свёрнутой панели её нет —
          тянуть нечего. */}
      {!leftCollapsed && (
        <div
          className="workspace__resizer"
          role="separator"
          aria-orientation="vertical"
          aria-label={t('panels.resizeLeft')}
          aria-valuenow={leftWidth.width}
          aria-valuemin={MIN_LEFT_WIDTH}
          aria-valuemax={MAX_LEFT_WIDTH}
          tabIndex={0}
          onDoubleClick={leftWidth.reset}
          {...leftWidth.handleProps}
        />
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
                {tab.alert && (
                  <span className="workspace__rail-dot">
                    {typeof tab.alert === 'string' && <span className="workspace__a11y-only">{tab.alert}</span>}
                  </span>
                )}
              </button>
            ))}
          </div>
        ))}
    </div>
  );
};

export default WorkspaceLayout;
