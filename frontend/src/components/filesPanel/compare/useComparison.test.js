import { renderHook, waitFor } from '@testing-library/react';
import useComparison from './useComparison';
import gitApi from '@/api/gitApi';

vi.mock('@/api/gitApi');

describe('useComparison', () => {
  afterEach(() => vi.resetAllMocks());

  test('without a base there is nothing to compare and nothing is asked', () => {
    const { result } = renderHook(() => useComparison({ project: 'kb', base: '', rev: '' }));

    expect(gitApi.compare).not.toHaveBeenCalled();
    expect(result.current.loading).toBe(false);
    expect(result.current.tracked).toEqual([]);
  });

  /** Список слева не должен знать, чьи изменения показывает. */
  test('hands the differing files out in the shape of the uncommitted list', async () => {
    const files = [{ status: 'M', path: 'a.js', additions: 1, deletions: 0 }];
    gitApi.compare.mockResolvedValue({ files, mergeBase: 'abc', diffBase: 'abc' });

    const { result } = renderHook(() => useComparison({ project: 'kb', base: 'main', rev: 'feature', direct: true }));

    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(gitApi.compare).toHaveBeenCalledWith(
      'main',
      expect.objectContaining({ head: 'feature', direct: true, project: 'kb' }),
    );
    expect(result.current.tracked).toEqual(files);
    expect(result.current.untracked).toEqual([]);
  });

  /** Сужение — другой ответ: сменилось — спрашивается заново, уже с ним. */
  test('narrowing to a path asks again with the scope', async () => {
    gitApi.compare.mockResolvedValue({ files: [] });
    const { rerender } = renderHook((props) => useComparison(props), {
      initialProps: { project: 'kb', base: 'main', rev: '' },
    });
    await waitFor(() => expect(gitApi.compare).toHaveBeenCalledTimes(1));

    rerender({ project: 'kb', base: 'main', rev: '', scope: 'docs' });

    await waitFor(() => expect(gitApi.compare).toHaveBeenCalledTimes(2));
    expect(gitApi.compare).toHaveBeenLastCalledWith('main', expect.objectContaining({ scope: 'docs' }));
  });

  /** Ветка после fetch называет другой коммит — и сравнение обязано это заметить. */
  test('asks again when the refs move', async () => {
    gitApi.compare.mockResolvedValue({ files: [] });
    const { rerender } = renderHook((props) => useComparison(props), {
      initialProps: { project: 'kb', base: 'origin/main', rev: '', refsToken: 0 },
    });
    await waitFor(() => expect(gitApi.compare).toHaveBeenCalledTimes(1));

    rerender({ project: 'kb', base: 'origin/main', rev: '', refsToken: 1 });

    await waitFor(() => expect(gitApi.compare).toHaveBeenCalledTimes(2));
  });

  test('a refused comparison is an error, not an empty list', async () => {
    gitApi.compare.mockRejectedValue(new Error('Commit not found: nope'));

    const { result } = renderHook(() => useComparison({ project: 'kb', base: 'nope', rev: '' }));

    await waitFor(() => expect(result.current.error).not.toBeNull());
    expect(result.current.loading).toBe(false);
  });
});
