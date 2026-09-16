import { useState, useRef, useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import {
  IconRefresh,
  IconCheck,
  IconChevron,
  IconDots,
  IconWorld,
  IconTool,
  IconSettings,
  IconSun,
  IconMoon,
  IconMonitor,
} from '@/icons/index';
import useDismissable from './useDismissable';
import useTheme, { THEMES } from './useTheme';
import './headerMenu.css';

const LANGS = [
  { code: 'ru', label: 'Русский' },
  { code: 'en', label: 'English' },
];

/** Иконка по выбору. У «как в системе» своя: она про источник, а не про цвет. */
const THEME_ICONS = { system: IconMonitor, light: IconSun, dark: IconMoon };

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
  const [themeOpen, setThemeOpen] = useState(false);
  const { theme, setTheme } = useTheme();
  const ref = useRef(null);
  const close = useCallback(() => setOpen(false), []);

  const lang = (i18n.language || 'ru').slice(0, 2);

  useDismissable(open, ref, close);

  // Меню всегда открывается кнопкой, поэтому подменю достаточно свернуть
  // здесь — закрыть их могут и клик снаружи, и Escape, а открыть только она.
  const toggle = () => {
    setOpen((o) => !o);
    setLangOpen(false);
    setThemeOpen(false);
  };

  const pickLang = (code) => {
    i18n.changeLanguage(code);
    close();
  };

  const ThemeIcon = THEME_ICONS[theme];

  return (
    <div className="header-menu" ref={ref}>
      <button
        className="header-menu__trigger"
        onClick={toggle}
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
            onClick={() => setThemeOpen((o) => !o)}
            aria-expanded={themeOpen}
          >
            <span className="header-menu__icon">
              <ThemeIcon size={16} />
            </span>
            <span className="header-menu__label">{t('menu.theme')}</span>
            <span className="header-menu__meta">
              {t(`menu.themes.${theme}`)}
              <IconChevron open={themeOpen} />
            </span>
          </button>

          {themeOpen && (
            <div className="header-menu__sub">
              {/* Меню не закрываем: тему выбирают глазами, и разница между «как в системе» и
                  выбранной руками видна только на самом экране — под закрывшимся меню. */}
              {THEMES.map((code) => (
                <button
                  key={code}
                  className={`header-menu__subitem${theme === code ? ' header-menu__subitem--active' : ''}`}
                  onClick={() => setTheme(code)}
                >
                  <span>{t(`menu.themes.${code}`)}</span>
                  {theme === code && (
                    <span className="header-menu__check">
                      <IconCheck />
                    </span>
                  )}
                </button>
              ))}
            </div>
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
