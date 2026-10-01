import { act, renderHook, waitFor } from '@testing-library/react';
import useKeyedRequest from './useKeyedRequest';

/** Запрос, ответ на который отдаётся вручную: так видно, что делает хук между запросом и ответом. */
function deferred() {
  const calls = [];
  const request = vi.fn(
    (signal) =>
      new Promise((resolve, reject) => {
        calls.push({ signal, resolve, reject });
      }),
  );
  return { request, calls };
}

describe('useKeyedRequest', () => {
  it('до ответа — загрузка, потом ответ', async () => {
    const { request, calls } = deferred();
    const { result } = renderHook(() => useKeyedRequest('a', request));

    expect(result.current).toEqual({ loading: true, value: null, error: null });
    await act(async () => calls[0].resolve('A'));
    expect(result.current).toEqual({ loading: false, value: 'A', error: null });
  });

  it('ключ null — ни запроса, ни загрузки', () => {
    const { request } = deferred();
    const { result } = renderHook(() => useKeyedRequest(null, request));

    expect(request).not.toHaveBeenCalled();
    expect(result.current).toEqual({ loading: false, value: null, error: null });
  });

  it('отказ — ошибка без значения', async () => {
    const failure = new Error('404');
    const { result } = renderHook(() => useKeyedRequest('a', () => Promise.reject(failure)));

    await waitFor(() => expect(result.current).toEqual({ loading: false, value: null, error: failure }));
  });

  it('отказ без причины — всё равно ошибка', async () => {
    const { result } = renderHook(() => useKeyedRequest('a', () => Promise.reject(undefined)));

    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.error).toBeInstanceOf(Error);
  });

  it('смена ключа: сразу загрузка, прошлый запрос отменён, его ответ отброшен', async () => {
    const { request, calls } = deferred();
    const { result, rerender } = renderHook((key) => useKeyedRequest(key, request), { initialProps: 'a' });

    await act(async () => calls[0].resolve('A'));
    rerender('b');
    expect(result.current).toEqual({ loading: true, value: null, error: null });

    rerender('c');
    expect(calls[1].signal.aborted).toBe(true);
    await act(async () => calls[1].resolve('B'));
    expect(result.current.value).toBeNull();

    await act(async () => calls[2].resolve('C'));
    expect(result.current.value).toBe('C');
  });

  it('вернувшийся ключ спрашивает заново и прежний ответ на него не показывает', async () => {
    const { request, calls } = deferred();
    const { result, rerender } = renderHook((key) => useKeyedRequest(key, request), { initialProps: 'a' });

    await act(async () => calls[0].resolve('A'));
    rerender('b');
    rerender('a');

    expect(result.current).toEqual({ loading: true, value: null, error: null });
    await act(async () => calls[2].resolve('A2'));
    expect(result.current.value).toBe('A2');
  });

  it('выключить и снова включить — тоже заново: прежний отказ не висит', async () => {
    const { request, calls } = deferred();
    const { result, rerender } = renderHook((key) => useKeyedRequest(key, request), { initialProps: 'a' });

    await act(async () => calls[0].reject(new Error('boom')));
    rerender(null);
    rerender('a');

    expect(result.current).toEqual({ loading: true, value: null, error: null });
  });

  it('размонтирование отменяет запрос', () => {
    const { request, calls } = deferred();
    const { unmount } = renderHook(() => useKeyedRequest('a', request));
    unmount();

    expect(calls[0].signal.aborted).toBe(true);
  });

  it('новая лямбда при том же ключе запрос не повторяет, но берётся свежая при смене ключа', () => {
    const seen = [];
    const { rerender } = renderHook(
      ({ key, tag }) =>
        useKeyedRequest(key, () => {
          seen.push(tag);
          return new Promise(() => {});
        }),
      { initialProps: { key: 'a', tag: 1 } },
    );
    rerender({ key: 'a', tag: 2 });
    rerender({ key: 'b', tag: 3 });

    expect(seen).toEqual([1, 3]);
  });
});
