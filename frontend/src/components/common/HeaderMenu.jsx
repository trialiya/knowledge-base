import React, { useState, useRef, useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import { IconRefresh, IconCheck, IconChevron, IconDots, IconWorld, IconTool, IconSettings } from '../../icons';
import './headerMenu.css';

const LANGS = [
  { code: 'ru', label: 'Русский' },
  { code: 'en', label: 'English' },
];

/**
 * Меню в правом верхнем углу шапки вкладок.
 * Заменяет собой прежние элементы «Обновить» и переключатель языка,
 * добавляя пункты «Админ-панель» и «Настройки чата».
 *
 * props:
 *   showRefresh  — показывать пункт «Обновить документ» (KB + открытый документ)
 *   refreshing   — документ обновляется (блокирует пункт, крутит иконку)
 *   onRefresh    — () => void
 *   onOpenAdmin  — () => void
 *   onOpenSettings — () => void
 */
const HeaderMenu = ({ showRefresh, refreshing, onRefresh, onOpenAdmin, onOpenSettings }) => {
  const { t, i18n } = useTranslation();
  const [open, setOpen] = useState(false);
  const [langOpen, setLangOpen] = useState(false);
  const ref = useRef(null);

  const lang = (i18n.language || 'ru').slice(0, 2);

  // Закрытие по клику снаружи и Escape
  useEffect(() => {
    if (!open) return;
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

  // Сворачиваем подменю языка, когда меню закрывается
  useEffect(() => {
    if (!open) setLangOpen(false);
  }, [open]);

  const close = () => setOpen(false);

  const pickLang = (code) => {
    i18n.changeLanguage(code);
    close();
  };

  return (
    <div className="header-menu" ref={ref}>
      <button
        className="header-menu__trigger"
        onClick={() => setOpen((o) => !o)}
        aria-haspopup="menu"
        aria-expanded={open}
        title={t('menu.title')}
      >
        <IconDots size={18} />
      </button>

      {open && (
        <div className="header-menu__dropdown" role="menu">
          {showRefresh && (
            <button
              className="header-menu__item"
              onClick={() => {
                onRefresh?.();
                close();
              }}
              disabled={refreshing}
            >
              <span className={`header-menu__icon${refreshing ? ' header-menu__icon--spin' : ''}`}>
                <IconRefresh size={15} />
              </span>
              <span className="header-menu__label">{t('menu.refreshDoc')}</span>
            </button>
          )}

          <button
            className="header-menu__item header-menu__item--toggle"
            onClick={() => setLangOpen((o) => !o)}
            aria-expanded={langOpen}
          >
            <span className="header-menu__icon">
              <IconWorld size={16} />
            </span>
            <span className="header-menu__label">{t('menu.language')}</span>
            <span className="header-menu__meta">
              {lang.toUpperCase()}
              <IconChevron open={langOpen} />
            </span>
          </button>

          {langOpen && (
            <div className="header-menu__sub">
              {LANGS.map(({ code, label }) => (
                <button
                  key={code}
                  className={`header-menu__subitem${lang === code ? ' header-menu__subitem--active' : ''}`}
                  onClick={() => pickLang(code)}
                >
                  <span>{label}</span>
                  {lang === code && (
                    <span className="header-menu__check">
                      <IconCheck />
                    </span>
                  )}
                </button>
              ))}
            </div>
          )}

          <div className="header-menu__divider" />

          <button
            className="header-menu__item"
            onClick={() => {
              onOpenAdmin?.();
              close();
            }}
          >
            <span className="header-menu__icon">
              <IconTool size={16} />
            </span>
            <span className="header-menu__label">{t('menu.admin')}</span>
          </button>

          <button
            className="header-menu__item"
            onClick={() => {
              onOpenSettings?.();
              close();
            }}
          >
            <span className="header-menu__icon">
              <IconSettings size={16} />
            </span>
            <span className="header-menu__label">{t('menu.settings')}</span>
          </button>
        </div>
      )}
    </div>
  );
};

export default HeaderMenu;
