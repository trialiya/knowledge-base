import { useState, useRef, useEffect, useCallback } from 'react';
import { IconChevronDown, IconCheck } from '../../icons';
import './listboxSelect.css';

/**
 * Компактный кастомный listbox-дропдаун (шире нативного <select>): ширина триггера
 * равна ширине текущего значения, стрелка вплотную к тексту, список читабелен.
 * Общая механика для выбора модели и режима в чате и выбора инструмента в
 * «Настройках» — не дублируем.
 *
 * Props:
 *   value     — id выбранного пункта
 *   options   — [{ id, label, note? }] (note — приглушённая пометка после label)
 *   onChange  — (id) => void
 *   disabled  — блокировка (например, во время стриминга)
 *   ariaLabel — доступное имя триггера/списка
 *   placement — 'down' (по умолчанию) или 'up' — для селектора у нижнего края
 *   className — доп. класс на корень (для позиционирования от места вставки)
 */
const ListboxSelect = ({
  value,
  options,
  onChange,
  disabled = false,
  ariaLabel,
  placement = 'down',
  className = '',
}) => {
  const [open, setOpen] = useState(false);
  const [activeIndex, setActiveIndex] = useState(-1);
  const rootRef = useRef(null);
  const buttonRef = useRef(null);
  const menuRef = useRef(null);
  const optionRefs = useRef([]);

  const list = options || [];
  const selectedIndex = list.findIndex((o) => o.id === value);
  const selected = selectedIndex >= 0 ? list[selectedIndex] : null;

  const close = useCallback(() => {
    setOpen(false);
    setActiveIndex(-1);
  }, []);

  // Открытие — из обработчика, а не из эффекта на `open`: подсветка текущего
  // пункта известна сразу и не стоит второго прохода рендера.
  const openMenu = () => {
    setOpen(true);
    setActiveIndex(selectedIndex >= 0 ? selectedIndex : 0);
  };

  // Стриминг начался во время открытого меню — закрываем прямо в рендере,
  // чтобы кадра с открытым меню поверх заблокированного триггера не было.
  const [prevDisabled, setPrevDisabled] = useState(disabled);
  if (prevDisabled !== disabled) {
    setPrevDisabled(disabled);
    if (disabled) {
      setOpen(false);
      setActiveIndex(-1);
    }
  }

  // Фокус на меню — чтобы клавиатура сразу попадала в список
  useEffect(() => {
    if (open) menuRef.current?.focus();
  }, [open]);

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
      openMenu();
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
    <div className={`lb-select${className ? ` ${className}` : ''}`} ref={rootRef}>
      <button
        type="button"
        ref={buttonRef}
        className="lb-select__trigger"
        disabled={disabled}
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-label={ariaLabel}
        title={ariaLabel}
        onClick={() => (open ? close() : openMenu())}
        onKeyDown={onTriggerKeyDown}
      >
        <span className="lb-select__label">
          {selected ? selected.label : ''}
          {selected?.note && <span className="lb-select__note"> {selected.note}</span>}
        </span>
        <IconChevronDown className={`lb-select__chevron${open ? ' lb-select__chevron--open' : ''}`} />
      </button>

      {open && (
        <ul
          className={`lb-select__menu${placement === 'up' ? ' lb-select__menu--up' : ''}`}
          role="listbox"
          aria-label={ariaLabel}
          tabIndex={-1}
          ref={menuRef}
          onKeyDown={onMenuKeyDown}
        >
          {list.map((o, i) => {
            const isSelected = o.id === value;
            const isActive = i === activeIndex;
            return (
              <li
                key={o.id}
                ref={(el) => {
                  optionRefs.current[i] = el;
                }}
                role="option"
                aria-selected={isSelected}
                className={
                  'lb-select__option' +
                  (isSelected ? ' lb-select__option--selected' : '') +
                  (isActive ? ' lb-select__option--active' : '')
                }
                onClick={() => commit(o.id)}
                onMouseEnter={() => setActiveIndex(i)}
              >
                <span className="lb-select__option-label">
                  {o.label}
                  {o.note && <span className="lb-select__note"> {o.note}</span>}
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

export default ListboxSelect;
