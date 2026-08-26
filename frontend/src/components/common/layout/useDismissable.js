import { useEffect } from 'react';

/**
 * Закрывает открытый поповер по клику вне него и по Escape.
 *
 * `mousedown`, а не `click`: выделение текста, начатое внутри и отпущенное
 * снаружи, иначе схлопывало бы меню под курсором пользователя.
 *
 * @param {boolean} open — пока false, слушателей нет вовсе
 * @param {React.RefObject|React.RefObject[]} ref — элемент, клик внутри которого
 *   закрытием не считается; несколько — когда поповер ушёл порталом и деревом
 *   DOM с триггером больше не связан. Массив должен переживать перерисовку
 *   (`useMemo`), иначе слушатели переподписываются на каждой.
 * @param {() => void} onClose
 */
export default function useDismissable(open, ref, onClose) {
  useEffect(() => {
    if (!open) return undefined;
    const refs = Array.isArray(ref) ? ref : [ref];
    const onDocClick = (e) => {
      if (!refs.some((r) => r.current?.contains(e.target))) onClose();
    };
    const onKey = (e) => {
      if (e.key === 'Escape') onClose();
    };
    document.addEventListener('mousedown', onDocClick);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onDocClick);
      document.removeEventListener('keydown', onKey);
    };
  }, [open, ref, onClose]);
}
