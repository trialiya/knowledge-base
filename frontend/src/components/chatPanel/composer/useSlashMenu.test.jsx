import { renderHook, act } from '@testing-library/react';
import useSlashMenu from './useSlashMenu';

describe('useSlashMenu', () => {
  it('открыт, пока набранному есть что показать', () => {
    const { result, rerender } = renderHook(({ value }) => useSlashMenu(value), {
      initialProps: { value: '/' },
    });

    expect(result.current.open).toBe(true);

    rerender({ value: 'обычный вопрос' });
    expect(result.current.open).toBe(false);
    expect(result.current.items).toEqual([]);
  });

  it('выбор ходит по списку и не выходит за его границы', () => {
    const { result } = renderHook(() => useSlashMenu('/'));
    const last = result.current.items.length - 1;

    act(() => result.current.move(1));
    expect(result.current.idx).toBe(1);

    act(() => result.current.move(10));
    expect(result.current.idx).toBe(last);

    act(() => result.current.move(-10));
    expect(result.current.idx).toBe(0);
  });

  // Escape закрывает список для того, что набрано сейчас, а не слэш вообще:
  // иначе дальше набирать пришлось бы вслепую до самой очистки поля.
  it('закрытие по Escape снимается следующим изменением текста', () => {
    const { result, rerender } = renderHook(({ value }) => useSlashMenu(value), {
      initialProps: { value: '/' },
    });

    act(() => result.current.dismiss());
    expect(result.current.open).toBe(false);

    rerender({ value: '/с' });
    expect(result.current.open).toBe(true);
  });

  it('выбор возвращается наверх, когда список пересобрался под новое набранное', () => {
    const { result, rerender } = renderHook(({ value }) => useSlashMenu(value), {
      initialProps: { value: '/' },
    });

    act(() => result.current.move(2));
    rerender({ value: '/с' });

    expect(result.current.idx).toBe(0);
  });
});
