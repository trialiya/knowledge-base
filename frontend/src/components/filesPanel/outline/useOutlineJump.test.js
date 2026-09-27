import { act, renderHook } from '@testing-library/react';
import useOutlineJump from './useOutlineJump';

describe('useOutlineJump', () => {
  /** Центр реагирует на новый объект: повторный клик по тому же символу тоже прокручивает. */
  test('каждый клик — новый прыжок, даже на ту же строку', () => {
    const { result } = renderHook(() => useOutlineJump('a.md'));

    act(() => result.current.onJump(5));
    const first = result.current.jump;
    act(() => result.current.onJump(5));

    expect(result.current.jump).toEqual({ path: 'a.md', line: 5 });
    expect(result.current.jump).not.toBe(first);
  });

  /** Только что открытый файл не должен прокрутиться к строке, выбранной в прошлом файле. */
  test('прыжок не переезжает на другой файл', () => {
    const { result, rerender } = renderHook((path) => useOutlineJump(path), { initialProps: 'a.md' });
    act(() => result.current.onJump(5));

    rerender('b.md');
    expect(result.current.jump).toBeNull();

    rerender('a.md');
    expect(result.current.jump).toBeNull();
  });
});
