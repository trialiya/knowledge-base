import React from 'react';
import './settingsShell.css';

/**
 * Общая «рамка» master-detail для страниц Админ-панель и Настройки.
 * Повторяет каркас базы знаний: слева — список групп (как дерево),
 * справа — рабочая область выбранной группы.
 *
 * props:
 *   title      — заголовок над списком групп (например, «Настройки»)
 *   groups     — [{ key, label, icon }]
 *   activeKey  — ключ активной группы
 *   onSelect   — (key) => void
 *   children   — содержимое активной группы (правая колонка)
 */
const SettingsShell = ({ title, groups, activeKey, onSelect, children }) => (
  <div className="settings-container">
    <div className="settings-main">
      <nav className="settings-nav">
        {title && <div className="settings-nav__title">{title}</div>}
        <div className="settings-nav__list">
          {groups.map((g) => (
            <button
              key={g.key}
              className={`settings-nav__item${activeKey === g.key ? ' settings-nav__item--active' : ''}`}
              onClick={() => onSelect(g.key)}
            >
              <span className="settings-nav__icon">{g.icon}</span>
              <span className="settings-nav__label">{g.label}</span>
            </button>
          ))}
        </div>
      </nav>

      <div className="settings-content">{children}</div>
    </div>
  </div>
);

/* Удобные подкомпоненты для правой колонки — чтобы страницы были компактнее. */

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
