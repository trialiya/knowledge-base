import React, { useState, useRef, useEffect, useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import { IconChevronDown, IconCheck } from '../../icons';

/**
 * Компактный выбор модели для активного чата. Рендерится в шапке после заголовка.
 *
 * Кастомный дропдаун вместо нативного <select>: стрелка всегда стоит вплотную
 * к названию модели (ширина = ширине текущего значения, а не самого длинного
 * варианта), а список раскрывается ВНИЗ под триггером и всегда читабелен.
 *
 * Props:
 *   value     — выбранный id модели (для пустой/дефолтной модели чата сюда
 *               прокидывается id дефолтной модели)
 *   defaultId — id дефолтной модели (для пометки «(по умолчанию)» в списке)
 *   options   — [{ id, label }] — список моделей (с дефолтной включительно)
 *   onChange  — вызывается с выбранным id
 *   disabled  — блокировка во время стриминга
 */
const ModelSelector = ({ value, defaultId, options, onChange, disabled = false }) => {
  const { t } = useTranslation('chat');
  const [open, setOpen] = useState(false);
  // Активный пункт под мышью/клавиатурой (единый «указатель» для обоих)
  const [activeIndex, setActiveIndex] = useState(-1);
  const rootRef = useRef(null);
  const buttonRef = useRef(null);
  const menuRef = useRef(null);
  const optionRefs = useRef([]);

  const list = options || [];
  const selectedIndex = list.findIndex((m) => m.id === value);
  const selected = selectedIndex >= 0 ? list[selectedIndex] : null;

  const close = useCallback(() => {
    setOpen(false);
    setActiveIndex(-1);
  }, []);

  // Стриминг начался во время открытого меню — закрываем
  useEffect(() => {
    if (disabled) close();
  }, [disabled, close]);

  // При открытии: подсветить текущую модель и сфокусировать меню (для клавиатуры)
  useEffect(() => {
    if (!open) return;
    setActiveIndex(selectedIndex >= 0 ? selectedIndex : 0);
    menuRef.current?.focus();
  }, [open]); // eslint-disable-line react-hooks/exhaustive-deps

  // Прокрутка к активному пункту при навигации стрелками
  useEffect(() => {
    if (open && activeIndex >= 0) {
      optionRefs.current[activeIndex]?.scrollIntoView({ block: 'nearest' });
    }
  }, [open, activeIndex]);

  // Закрытие по клику вне компонента
  useEffect(() => {
    if (!open) return;
    const onDocDown = (e) => {
      if (rootRef.current && !rootRef.current.contains(e.target)) close();
    };
    document.addEventListener('mousedown', onDocDown);
    return () => document.removeEventListener('mousedown', onDocDown);
  }, [open, close]);

  if (list.length === 0) return null;

  const commit = (id) => {
    onChange(id);
    close();
    buttonRef.current?.focus();
  };

  const onTriggerKeyDown = (e) => {
    if (disabled) return;
    if ((e.key === 'ArrowDown' || e.key === 'Enter' || e.key === ' ') && !open) {
      e.preventDefault();
      setOpen(true);
    }
  };

  const onMenuKeyDown = (e) => {
    switch (e.key) {
      case 'ArrowDown':
        e.preventDefault();
        setActiveIndex((i) => (i + 1) % list.length);
        break;
      case 'ArrowUp':
        e.preventDefault();
        setActiveIndex((i) => (i - 1 + list.length) % list.length);
        break;
      case 'Home':
        e.preventDefault();
        setActiveIndex(0);
        break;
      case 'End':
        e.preventDefault();
        setActiveIndex(list.length - 1);
        break;
      case 'Enter':
      case ' ':
        e.preventDefault();
        if (activeIndex >= 0) commit(list[activeIndex].id);
        break;
      case 'Escape':
        e.preventDefault();
        close();
        buttonRef.current?.focus();
        break;
      case 'Tab':
        close();
        break;
      default:
        break;
    }
  };

  return (
    <div className="chat-model-selector" ref={rootRef}>
      <button
        type="button"
        ref={buttonRef}
        className="chat-model-trigger"
        disabled={disabled}
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-label={t('model.aria')}
        title={t('model.aria')}
        onClick={() => (open ? close() : setOpen(true))}
        onKeyDown={onTriggerKeyDown}
      >
        <span className="chat-model-trigger__label">
          {selected ? selected.label : ''}
          {selected && selected.id === defaultId && (
            <span className="chat-model-trigger__default"> ({t('model.default')})</span>
          )}
        </span>
        <IconChevronDown className={`chat-model-chevron${open ? ' chat-model-chevron--open' : ''}`} />
      </button>

      {open && (
        <ul
          className="chat-model-menu"
          role="listbox"
          aria-label={t('model.aria')}
          tabIndex={-1}
          ref={menuRef}
          onKeyDown={onMenuKeyDown}
        >
          {list.map((m, i) => {
            const isSelected = m.id === value;
            const isActive = i === activeIndex;
            return (
              <li
                key={m.id}
                ref={(el) => {
                  optionRefs.current[i] = el;
                }}
                role="option"
                aria-selected={isSelected}
                className={
                  'chat-model-option' +
                  (isSelected ? ' chat-model-option--selected' : '') +
                  (isActive ? ' chat-model-option--active' : '')
                }
                onClick={() => commit(m.id)}
                onMouseEnter={() => setActiveIndex(i)}
              >
                <span className="chat-model-option__label">
                  {m.label}
                  {m.id === defaultId && <span className="chat-model-option__default"> ({t('model.default')})</span>}
                </span>
                {isSelected && <IconCheck />}
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
};

export default ModelSelector;
