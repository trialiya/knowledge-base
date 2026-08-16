import { useState } from 'react';
import { useTranslation } from 'react-i18next';

// Шапка вида и раскрытие «всё / ничего». Общее для всех видов со списками:
// diff, список записей, дерево, совпадения — у всех строка-итог сверху и
// сворачиваемые элементы под ней, и вести это врозь незачем.

/**
 * Состояние «что раскрыто» с кнопками на весь список.
 *
 * `keys` — все сворачиваемые элементы вида; `initial` уходит в `useState` как
 * есть, поэтому дорогой расчёт передают функцией (ленивая инициализация React).
 */
export const useExpandAll = (keys, initial) => {
  const [open, setOpen] = useState(initial);
  return {
    isOpen: (key) => !!open[key],
    toggle: (key) => setOpen((prev) => ({ ...prev, [key]: !prev[key] })),
    setAll: (value) => setOpen(Object.fromEntries(keys.map((key) => [key, value]))),
    allOpen: keys.length > 0 && keys.every((key) => open[key]),
    noneOpen: keys.every((key) => !open[key]),
  };
};

/**
 * Строка-итог над списком: слева — сколько чего, справа — «развернуть всё» и
 * «свернуть всё». Кнопка, которая ничего не изменит, заблокирована, а не
 * спрятана: пропадающая кнопка сдвигает соседнюю под курсором.
 */
const ResultSummary = ({ expand, children }) => {
  const { t } = useTranslation('chat');

  return (
    <div className="tool-summary">
      <span className="tool-summary__text">{children}</span>
      {expand && (
        <span className="tool-summary__actions">
          <button
            type="button"
            className="tool-summary__action"
            onClick={() => expand.setAll(true)}
            disabled={expand.allOpen}
          >
            {t('toolCall.detail.expandAll')}
          </button>
          <button
            type="button"
            className="tool-summary__action"
            onClick={() => expand.setAll(false)}
            disabled={expand.noneOpen}
          >
            {t('toolCall.detail.collapseAll')}
          </button>
        </span>
      )}
    </div>
  );
};

export default ResultSummary;
