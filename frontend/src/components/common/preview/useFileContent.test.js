import { renderHook } from '@testing-library/react';
import useFileContent from './useFileContent';

// Свежесть ответа, отмена и отказ — в useKeyedRequest.test.js; здесь — что и как хук спрашивает.

const pending = () => vi.fn(() => new Promise(() => {}));

describe('useFileContent', () => {
  it('спрашивает файл со строками, ревизией и проектом — и с сигналом отмены', () => {
    const read = pending();
    const { result } = renderHook(() =>
      useFileContent({ path: 'a.md', project: 'kb', rev: 'abc', from: 3, to: 5, read }),
    );

    expect(result.current).toEqual({ loading: true, file: null, error: false });
    expect(read).toHaveBeenCalledWith('a.md', {
      from: 3,
      to: 5,
      rev: 'abc',
      project: 'kb',
      signal: expect.any(AbortSignal),
    });
  });

  it('enabled: false — ни запроса, ни загрузки', () => {
    const read = pending();
    const { result } = renderHook(() => useFileContent({ path: 'a.md', enabled: false, read }));

    expect(read).not.toHaveBeenCalled();
    expect(result.current).toEqual({ loading: false, file: null, error: false });
  });

  it("пустые проект и ревизия — тот же запрос, что и null: '' не перечитывает файл", () => {
    const read = pending();
    const { rerender } = renderHook((project) => useFileContent({ path: 'a.md', project, rev: '', read }), {
      initialProps: null,
    });
    rerender('');

    expect(read).toHaveBeenCalledTimes(1);
  });

  it('лямбда на месте read не перечитывает файл на каждом рендере', () => {
    const calls = pending();
    const { rerender } = renderHook(() => useFileContent({ path: 'a.md', read: (...args) => calls(...args) }));
    rerender();
    rerender();

    expect(calls).toHaveBeenCalledTimes(1);
  });
});
