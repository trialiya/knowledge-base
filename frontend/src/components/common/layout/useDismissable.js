import { useEffect } from 'react';

/**
 * Закрывает открытый поповер по клику вне него и по Escape.
 *
 * `mousedown`, а не `click`: выделение текста, начатое внутри и отпущенное
 * снаружи, иначе схлопывало бы меню под курсором пользователя.
 *
 * @param {boolean} open — пока false, слушателей нет вовсе
 * @param {React.RefObject} ref — элемент, клик внутри которого закрытием не считается
 * @param {() => void} onClose
 */
export default function useDismissable(open, ref, onClose) {
  useEffect(() => {
    if (!open) return undefined;
    const onDocClick = (e) => {
      if (ref.current && !ref.current.contains(e.target)) onClose();
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
