import { useEffect, useRef } from 'react';

/**
 * Оболочка выпадающего списка композера: рамка, шапка с подсказкой, прокрутка к
 * выбранной строке, футер с клавишами и закрытие по клику мимо. Строки рисует
 * тот, кто список открыл, — их анатомия у поиска файлов и у списка со слэша
 * разная, а вот всё вокруг них должно совпадать до пикселя.
 *
 * Props:
 *   className    — модификатор позиции (список со слэша встаёт над полем)
 *   style        — позиция, когда её считает вызывающий (поиск — по каретке)
 *   hint         — строка шапки
 *   above        — строки над списком: «идёт поиск», «ничего не найдено»
 *   footer       — подсказка по клавишам
 *   selectedIdx  — индекс выбранной строки: по его смене прокручиваем к ней список
 *   onDismiss()  — закрыть (клик мимо)
 */
const PickerDropdown = ({ className = '', style, hint, above, footer, selectedIdx = 0, onDismiss, children }) => {
  const listRef = useRef(null);

  useEffect(() => {
    // Ищем выбранную строку, а не children[selectedIdx]: между строками бывают
    // заголовки разделов, и по индексу прокрутка уехала бы на соседнюю.
    listRef.current?.querySelector('.picker-item--selected')?.scrollIntoView({ block: 'nearest' });
  }, [selectedIdx]);

  useEffect(() => {
    const handler = (e) => {
      if (!e.target.closest?.('.picker-dropdown')) onDismiss();
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, [onDismiss]);

  return (
    <div className={`picker-dropdown${className ? ` ${className}` : ''}`} style={style}>
      <div className="picker-dropdown__header">
        <span className="picker-dropdown__hint">{hint}</span>
      </div>

      {above}

      <div className="picker-dropdown__list" ref={listRef}>
        {children}
      </div>

      <div className="picker-dropdown__footer">{footer}</div>
    </div>
  );
};

export default PickerDropdown;
