import { act, renderHook, waitFor } from '@testing-library/react';
import useFileContent from './useFileContent';

/** Чтение, ответ на которое отдаётся вручную: так видно, что делает хук между запросом и ответом. */
function deferredRead() {
  const calls = [];
  const read = vi.fn(
    (path, opts) =>
      new Promise((resolve, reject) => {
        calls.push({ path, opts, resolve, reject });
      }),
  );
  return { read, calls };
}

describe('useFileContent', () => {
  it('читает файл и отдаёт ответ; до ответа — загрузка', async () => {
    const { read, calls } = deferredRead();
    const { result } = renderHook(() =>
      useFileContent({ path: 'a.md', project: 'kb', rev: 'abc', from: 3, to: 5, read }),
    );

    expect(result.current).toEqual({ loading: true, file: null, error: false });
    expect(read).toHaveBeenCalledWith('a.md', { from: 3, to: 5, rev: 'abc', project: 'kb' });

    await act(async () => calls[0].resolve({ content: 'x' }));
    expect(result.current).toEqual({ loading: false, file: { content: 'x' }, error: false });
  });

  it('enabled: false — ни запроса, ни загрузки', () => {
    const { read } = deferredRead();
    const { result } = renderHook(() => useFileContent({ path: 'a.md', enabled: false, read }));

    expect(read).not.toHaveBeenCalled();
    expect(result.current).toEqual({ loading: false, file: null, error: false });
  });

  it('отказ чтения — ошибка без файла', async () => {
    const read = vi.fn(() => Promise.reject(new Error('404')));
    const { result } = renderHook(() => useFileContent({ path: 'a.md', read }));

    await waitFor(() => expect(result.current).toEqual({ loading: false, file: null, error: true }));
  });

  it('смена файла: сразу загрузка, а опоздавший ответ прежнего отброшен', async () => {
    const { read, calls } = deferredRead();
    const { result, rerender } = renderHook((path) => useFileContent({ path, read }), { initialProps: 'a.md' });

    await act(async () => calls[0].resolve({ content: 'a' }));
    rerender('b.md');
    expect(result.current).toEqual({ loading: true, file: null, error: false });

    rerender('c.md');
    await act(async () => calls[1].resolve({ content: 'b' }));
    expect(result.current.file).toBeNull();

    await act(async () => calls[2].resolve({ content: 'c' }));
    expect(result.current.file).toEqual({ content: 'c' });
  });

  it("пустые проект и ревизия — тот же запрос, что и null: '' не перечитывает файл", () => {
    const { read } = deferredRead();
    const { rerender } = renderHook((project) => useFileContent({ path: 'a.md', project, rev: '', read }), {
      initialProps: null,
    });
    rerender('');

    expect(read).toHaveBeenCalledTimes(1);
  });
});
