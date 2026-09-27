import { act, renderHook, waitFor } from '@testing-library/react';
import useFileOutline from './useFileOutline';
import gitApi from '@/api/gitApi';

vi.mock('@/api/gitApi');

/** Ответ, который тест отдаёт сам, когда захочет. */
const deferred = () => {
  let resolve;
  const promise = new Promise((r) => {
    resolve = r;
  });
  return { promise, resolve };
};

const sym = (name) => ({ kind: 'function', name, startLine: 1, endLine: 1 });

describe('useFileOutline', () => {
  afterEach(() => vi.resetAllMocks());

  /** Пока не пришёл ответ о новом файле, показывать структуру прошлого нельзя: клик увёл бы не туда. */
  test('другой файл — сразу загрузка, а не структура прошлого', async () => {
    gitApi.getFileOutline.mockResolvedValueOnce({ symbols: [sym('a')] });
    const b = deferred();
    gitApi.getFileOutline.mockReturnValueOnce(b.promise);

    const { result, rerender } = renderHook((props) => useFileOutline(props), {
      initialProps: { path: 'a.js', project: 'kb' },
    });
    await waitFor(() => expect(result.current.symbols).toEqual([sym('a')]));

    rerender({ path: 'b.js', project: 'kb' });
    expect(result.current).toEqual({ symbols: [], loading: true, error: false });

    await act(async () => b.resolve({ symbols: [sym('b')] }));
    expect(result.current.symbols).toEqual([sym('b')]);
  });

  /** Медленный ответ о прошлом файле, пришедший после ответа о новом, его не перетирает. */
  test('запоздавший ответ о прошлом файле не перетирает текущий', async () => {
    const a = deferred();
    gitApi.getFileOutline.mockReturnValueOnce(a.promise);
    gitApi.getFileOutline.mockResolvedValueOnce({ symbols: [sym('b')] });

    const { result, rerender } = renderHook((props) => useFileOutline(props), {
      initialProps: { path: 'a.js', project: 'kb' },
    });
    rerender({ path: 'b.js', project: 'kb' });
    await waitFor(() => expect(result.current.symbols).toEqual([sym('b')]));

    await act(async () => a.resolve({ symbols: [sym('a')] }));
    expect(result.current.symbols).toEqual([sym('b')]);
  });
});
