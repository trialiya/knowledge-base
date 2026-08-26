import { useTranslation } from 'react-i18next';
import { IconChevronRight } from '@/icons/index';

/**
 * Правая панель рабочей области — раскрытое состояние.
 *
 * Рисует шапку с вкладками (или одним заголовком, если вкладка единственная),
 * кнопку сворачивания и тело активной вкладки. Свёрнутое состояние — это уже
 * не этот компонент, а «рельс» в WorkspaceLayout: там панель представлена
 * колонкой иконок, каждая из которых раскрывает свою вкладку.
 *
 * props:
 *   tabs      — [{ key, label, icon, badge, alert, content }] (content — узел)
 *               badge — число (сколько), alert — точка (требует внимания:
 *               счётчика у такого состояния нет, а сказать о нём надо и
 *               свёрнутой панели)
 *   activeKey — ключ раскрытой вкладки
 *   onTabChange — (key) => void
 *   onClose   — () => void
 */
const RightPanel = ({ tabs, activeKey, onTabChange, onClose }) => {
  const { t } = useTranslation();
  const active = tabs.find((tab) => tab.key === activeKey) || tabs[0];
  const single = tabs.length === 1;

  return (
    <aside className="workspace__side workspace__side--right" aria-label={active?.label}>
      <div className="workspace__side-head">
        {single ? (
          <span className="workspace__side-title">
            {active.icon}
            {active.label}
            {active.badge > 0 && <span className="workspace__tab-badge">{active.badge}</span>}
            {active.alert && <span className="workspace__tab-dot" aria-hidden="true" />}
          </span>
        ) : (
          <div className="workspace__tabs" role="tablist">
            {tabs.map((tab) => (
              <button
                key={tab.key}
                type="button"
                role="tab"
                id={`ws-tab-${tab.key}`}
                aria-selected={tab.key === active.key}
                aria-controls="ws-tabpanel"
                className={`workspace__tab${tab.key === active.key ? ' workspace__tab--active' : ''}`}
                onClick={() => onTabChange(tab.key)}
              >
                {tab.label}
                {tab.badge > 0 && <span className="workspace__tab-badge">{tab.badge}</span>}
                {tab.alert && <span className="workspace__tab-dot" aria-hidden="true" />}
              </button>
            ))}
          </div>
        )}
        <button
          type="button"
          className="icon-btn"
          onClick={onClose}
          title={t('panels.collapseRight')}
          aria-label={t('panels.collapseRight')}
        >
          <IconChevronRight size={14} />
        </button>
      </div>
      <div
        className="workspace__side-body"
        id="ws-tabpanel"
        role={single ? undefined : 'tabpanel'}
        aria-labelledby={single ? undefined : `ws-tab-${active?.key}`}
      >
        {active?.content}
      </div>
    </aside>
  );
};

export default RightPanel;
