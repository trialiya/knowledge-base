import { renderHook, waitFor } from '@testing-library/react';
import useFileBlame from './useFileBlame';
import gitApi from '@/api/gitApi';

vi.mock('@/api/gitApi');

const hunks = [{ fromLine: 1, lineCount: 2, hash: 'a'.repeat(40), shortHash: 'aaaaaaa', author: 'Alice' }];

describe('useFileBlame', () => {
  afterEach(() => vi.resetAllMocks());

  test('asks nothing while disabled', () => {
    const { result } = renderHook(() => useFileBlame({ path: 'a.js', project: 'kb', rev: '', enabled: false }));

    expect(gitApi.getBlame).not.toHaveBeenCalled();
    expect(result.current.loading).toBe(false);
    expect(result.current.hunks).toEqual([]);
  });

  test('hands the hunks out with the revision and project they were asked for', async () => {
    gitApi.getBlame.mockResolvedValue({ path: 'a.js', hunks });

    const { result } = renderHook(() => useFileBlame({ path: 'a.js', project: 'kb', rev: 'v1', enabled: true }));

    expect(result.current.loading).toBe(true);
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(gitApi.getBlame).toHaveBeenCalledWith('a.js', expect.objectContaining({ project: 'kb', rev: 'v1' }));
    expect(result.current.hunks).toEqual(hunks);
  });

  /** После коммита, pull или отката строки принадлежат уже другим коммитам. */
  test('asks again when the repository changed under the file', async () => {
    gitApi.getBlame.mockResolvedValue({ path: 'a.js', hunks });
    const { result, rerender } = renderHook(
      ({ reloadToken }) => useFileBlame({ path: 'a.js', project: 'kb', rev: '', reloadToken, enabled: true }),
      { initialProps: { reloadToken: 1 } },
    );
    await waitFor(() => expect(result.current.loading).toBe(false));

    rerender({ reloadToken: 2 });

    expect(result.current.loading).toBe(true);
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(gitApi.getBlame).toHaveBeenCalledTimes(2);
  });

  /** Ответ на прошлый файл не должен подписать строки следующего. */
  test('an answer for the previous file is not shown on the next one', async () => {
    let resolveFirst;
    gitApi.getBlame.mockImplementationOnce(() => new Promise((resolve) => (resolveFirst = resolve)));
    gitApi.getBlame.mockResolvedValueOnce({ path: 'b.js', hunks: [] });
    const { result, rerender } = renderHook(
      ({ path }) => useFileBlame({ path, project: 'kb', rev: '', enabled: true }),
      {
        initialProps: { path: 'a.js' },
      },
    );

    rerender({ path: 'b.js' });
    resolveFirst({ path: 'a.js', hunks });

    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.hunks).toEqual([]);
  });

  test('a refused blame is an error, not an empty column', async () => {
    gitApi.getBlame.mockRejectedValue(new Error('503'));

    const { result } = renderHook(() => useFileBlame({ path: 'a.js', project: 'kb', rev: '', enabled: true }));

    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.error).toBeTruthy();
    expect(result.current.hunks).toEqual([]);
  });
});
