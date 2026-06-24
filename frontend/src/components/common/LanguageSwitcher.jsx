import React, { useState, useRef, useEffect, useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import { IconWorld, IconChevronDown } from '../../icons';
import './languageSwitcher.css';

const LANGS = [
  { code: 'ru', label: 'Русский', short: 'RU' },
  { code: 'en', label: 'English', short: 'EN' },
];

/**
 * Переключатель языка: компактная кнопка (глобус + код языка) с выпадающим меню.
 * Завязан на i18next — changeLanguage сам кэширует выбор в localStorage (ключ kb-lang).
 *
 * Поведение меню:
 *   • клик по кнопке — открыть/закрыть
 *   • клик вне компонента — закрыть
 *   • Esc — закрыть и вернуть фокус на кнопку
 */
export default function LanguageSwitcher() {
  const { i18n } = useTranslation();
  const [open, setOpen] = useState(false);
  const rootRef = useRef(null);
  const btnRef = useRef(null);

  const current = (i18n.resolvedLanguage || i18n.language || 'ru').split('-')[0];
  const currentLang = LANGS.find((l) => l.code === current) || LANGS[0];

  const close = useCallback(() => setOpen(false), []);

  // Закрытие по клику снаружи
  useEffect(() => {
    if (!open) return undefined;
    const onDocClick = (e) => {
      if (rootRef.current && !rootRef.current.contains(e.target)) close();
    };
    document.addEventListener('mousedown', onDocClick);
    return () => document.removeEventListener('mousedown', onDocClick);
  }, [open, close]);

  // Закрытие по Esc
  useEffect(() => {
    if (!open) return undefined;
    const onKey = (e) => {
      if (e.key === 'Escape') {
        close();
        btnRef.current?.focus();
      }
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [open, close]);

  const choose = (code) => {
    i18n.changeLanguage(code);
    close();
    btnRef.current?.focus();
  };

  return (
    <div className="lang-switch" ref={rootRef}>
      <button
        ref={btnRef}
        type="button"
        className="lang-switch__trigger"
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-label="Язык интерфейса"
        onClick={() => setOpen((v) => !v)}
      >
        <IconWorld className="lang-switch__globe" size={16} />
        <span className="lang-switch__code">{currentLang.short}</span>
        <IconChevronDown className={`lang-switch__chevron${open ? ' lang-switch__chevron--open' : ''}`} size={12} />
      </button>

      {open && (
        <ul className="lang-switch__menu" role="listbox" aria-label="Выбор языка">
          {LANGS.map(({ code, label, short }) => (
            <li key={code} role="option" aria-selected={current === code}>
              <button
                type="button"
                className={`lang-switch__item${current === code ? ' lang-switch__item--active' : ''}`}
                onClick={() => choose(code)}
              >
                <span className="lang-switch__item-short">{short}</span>
                <span className="lang-switch__item-label">{label}</span>
                {current === code && <span className="lang-switch__check">✓</span>}
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
