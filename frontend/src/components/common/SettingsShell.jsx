import WorkspaceLayout from './WorkspaceLayout';
import useListNavigation from '../../hooks/useListNavigation';
import './settingsShell.css';

/**
 * Каркас master-detail для страниц «Админ-панель» и «Настройки».
 *
 * Раскладка — общая для всего приложения (WorkspaceLayout): список групп это
 * левая скрываемая панель, содержимое группы — центр. Раньше здесь был свой
 * контейнер, повторявший каркас базы знаний; после перехода разделов на общую
 * оболочку копия стала лишней и разъезжалась бы с ней при любой правке.
 * Правой панели у этих страниц нет — рассказывать сбоку не о чем.
 *
 * props:
 *   title      — заголовок над списком групп (например, «Настройки»)
 *   groups     — [{ key, label, icon }]
 *   activeKey  — ключ активной группы
 *   onSelect   — (key) => void
 *   panels     — раскладка панелей из навигации (см. App)
 *   children   — содержимое активной группы (центр)
 */
const SettingsShell = ({ title, groups, activeKey, onSelect, panels, children }) => {
  // Строки здесь — настоящие <button> (Tab и Enter работают сами), стрелки
  // добавляем для единообразия со списками и деревьями остальных разделов.
  // Поэтому и tabIndex у контейнера нет: в остальных разделах он единственная
  // точка входа только потому, что там строки — неинтерактивные div/li.
  const handleKeyDown = useListNavigation();

  return (
    <WorkspaceLayout
      {...panels}
      left={{
        title,
        children: (
          // role="group" — чтобы aria-label был законным именем: на div без роли
          // (role=generic) ARIA запрещает aria-label, и скринридер его теряет.
          <div className="ws-list" role="group" aria-label={title} onKeyDown={handleKeyDown}>
            {groups.map((g) => (
              <button
                key={g.key}
                type="button"
                data-ws-item
                aria-current={activeKey === g.key ? 'true' : undefined}
                className={`ws-item${activeKey === g.key ? ' ws-item--active' : ''}`}
                onClick={() => onSelect(g.key)}
              >
                <span className="ws-item__icon">{g.icon}</span>
                <span className="ws-item__label">{g.label}</span>
              </button>
            ))}
          </div>
        ),
      }}
      center={children}
    />
  );
};

/* Удобные подкомпоненты для центра — чтобы страницы были компактнее. */

export const SettingsContentHead = ({ title, subtitle, actions }) => (
  <div className="settings-content__head">
    <div className="settings-content__head-text">
      <h2 className="settings-content__title">{title}</h2>
      {subtitle && <p className="settings-content__sub">{subtitle}</p>}
    </div>
    {actions && <div className="settings-content__head-actions">{actions}</div>}
  </div>
);

export const SettingsSection = ({ label, action, rows = false, children }) => (
  <section className="set-section">
    {(label || action) && (
      <div className="set-section__head">
        <span className="set-section__label">{label}</span>
        {action}
      </div>
    )}
    <div className={`set-section__body${rows ? ' set-section__body--rows' : ''}`}>{children}</div>
  </section>
);

export default SettingsShell;
