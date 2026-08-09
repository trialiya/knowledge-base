import { renderHook } from '@testing-library/react';
import useEscape from './useEscape';

/**
 * Хук существует ради одного свойства: слушатель ставится один раз, а зовёт
 * всегда свежий колбэк. На нём держится ModalShell — он передаёт сюда инлайновую
 * функцию, читающую open/onClose текущего рендера, и не мемоизирует её.
 */
describe('useEscape', () => {
  function pressEscape() {
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
  }

  it('зовёт колбэк последнего рендера, хотя тот пересоздаётся каждый раз', () => {
    const first = vi.fn();
    const second = vi.fn();

    const { rerender } = renderHook(({ cb }) => useEscape(() => cb()), {
      initialProps: { cb: first },
    });

    pressEscape();
    expect(first).toHaveBeenCalledTimes(1);

    rerender({ cb: second });
    pressEscape();
    expect(second).toHaveBeenCalledTimes(1);
    expect(first).toHaveBeenCalledTimes(1); // старый колбэк больше не зовут
  });

  it('другие клавиши не трогает, а после размонтирования молчит', () => {
    const cb = vi.fn();
    const { unmount } = renderHook(() => useEscape(cb));

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));
    expect(cb).not.toHaveBeenCalled();

    unmount();
    pressEscape();
    expect(cb).not.toHaveBeenCalled();
  });
});
