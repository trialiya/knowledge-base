import { renderHook, waitFor } from '@testing-library/react';
import useSnapshotCommit from './useSnapshotCommit';
import gitApi from '@/api/gitApi';

vi.mock('@/api/gitApi');

describe('useSnapshotCommit', () => {
  afterEach(() => vi.resetAllMocks());

  test('the working tree has no commit of its own and asks nothing', () => {
    const { result } = renderHook(() => useSnapshotCommit({ project: 'kb', rev: '', refsToken: 0 }));

    expect(gitApi.getCommit).not.toHaveBeenCalled();
    expect(result.current.loading).toBe(false);
    expect(result.current.tracked).toEqual([]);
  });

  /** Без открытой вкладки «Коммит» и режима «Изменения» список файлов коммита не нужен никому. */
  test('asks nothing while disabled', () => {
    const { result } = renderHook(() => useSnapshotCommit({ project: 'kb', rev: 'main', enabled: false }));

    expect(gitApi.getCommit).not.toHaveBeenCalled();
    expect(result.current.loading).toBe(false);
  });

  /** Список слева не должен знать, чьи изменения показывает. */
  test('hands the commit files out in the shape of the uncommitted list', async () => {
    const files = [{ status: 'M', path: 'a.js', additions: 1, deletions: 0 }];
    gitApi.getCommit.mockResolvedValue({ hash: 'abc', message: 'm', files });

    const { result } = renderHook(() => useSnapshotCommit({ project: 'kb', rev: 'main', refsToken: 0 }));

    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(gitApi.getCommit).toHaveBeenCalledWith('main', expect.objectContaining({ project: 'kb' }));
    expect(result.current.commit.hash).toBe('abc');
    expect(result.current.tracked).toEqual(files);
    expect(result.current.untracked).toEqual([]);
  });

  /** Ветка после коммита, pull или switch — и remote-ветка после fetch — называет уже другой коммит. */
  test.each([
    ['refreshToken', { refreshToken: 1, refsToken: 0 }],
    ['refsToken', { refreshToken: 0, refsToken: 1 }],
  ])('asks again when %s moves', async (_, moved) => {
    gitApi.getCommit.mockResolvedValue({ hash: 'abc', files: [] });
    const { rerender, result } = renderHook((tokens) => useSnapshotCommit({ project: 'kb', rev: 'main', ...tokens }), {
      initialProps: { refreshToken: 0, refsToken: 0 },
    });
    await waitFor(() => expect(result.current.loading).toBe(false));

    rerender(moved);

    await waitFor(() => expect(gitApi.getCommit).toHaveBeenCalledTimes(2));
  });
});
