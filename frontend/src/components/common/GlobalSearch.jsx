import React, { useState, useRef, useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import { IconSearch, IconSliders, IconCheck } from '../../icons';
import './globalSearch.css';

const MODES = ['hybrid', 'semantic', 'keyword'];

/**
 * Глобальный поиск в шапке вкладок: единый визуальный контур
 * (лупа + input + подсказка Enter + выбор режима в поповере).
 * Заменяет прежнюю пару «input + select» из .app-search-row.
 *
 * props:
 *   value        — текст запроса (controlled)
 *   mode         — 'hybrid' | 'semantic' | 'keyword'
 *   onChange     — (text) => void
 *   onModeChange — (mode) => void
 *   onSubmit     — () => void (Enter в поле)
 */
const GlobalSearch = ({ value, mode, onChange, onModeChange, onSubmit }) => {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  const modeRef = useRef(null);

  // Закрытие поповера по клику снаружи и Escape (как в HeaderMenu)
  useEffect(() => {
    if (!open) return;
    const onDocClick = (e) => {
      if (modeRef.current && !modeRef.current.contains(e.target)) setOpen(false);
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

  const pickMode = (m) => {
    setOpen(false);
    onModeChange(m);
  };

  return (
    <div className="global-search">
      <span className="global-search__icon">
        <IconSearch size={15} />
      </span>
      <input
        type="text"
        placeholder={t('search.placeholder')}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === 'Enter') onSubmit();
        }}
      />
      <span className="global-search__hint" aria-hidden="true">
        ↵ Enter
      </span>
      <span className="global-search__divider" />
      <div className="global-search__mode" ref={modeRef}>
        <button
          className="global-search__mode-btn"
          onClick={() => setOpen((o) => !o)}
          aria-haspopup="menu"
          aria-expanded={open}
          aria-label={t('search.modeTitle')}
          title={t('search.modeTitle')}
        >
          <IconSliders size={15} />
        </button>
        {open && (
          <div className="global-search__pop" role="menu">
            {MODES.map((m) => (
              <button
                key={m}
                role="menuitemradio"
                aria-checked={mode === m}
                className={`global-search__pop-item${mode === m ? ' global-search__pop-item--active' : ''}`}
                onClick={() => pickMode(m)}
              >
                <span>{t(`search.${m}`)}</span>
                {mode === m && (
                  <span className="global-search__check">
                    <IconCheck />
                  </span>
                )}
              </button>
            ))}
          </div>
        )}
      </div>
    </div>
  );
};

export default GlobalSearch;
