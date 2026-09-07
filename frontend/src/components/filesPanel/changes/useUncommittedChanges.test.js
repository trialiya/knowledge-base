import { act, renderHook, waitFor } from '@testing-library/react';
import gitApi from '@/api/gitApi';
import useUncommittedChanges from './useUncommittedChanges';

vi.mock('@/api/gitApi');

const entry = (path, status) => ({ status, path, oldPath: null, additions: 0, deletions: 0, patch: null });

describe('useUncommittedChanges', () => {
  afterEach(() => {
    vi.resetAllMocks();
  });

  test('splits the answer into tracked and untracked', async () => {
    gitApi.getStatus.mockResolvedValue([entry('a.js', 'M'), entry('new.txt', 'U'), entry('gone.js', 'D')]);

    const { result } = renderHook(() => useUncommittedChanges({ project: 'kb', enabled: true }));

    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.tracked.map((e) => e.path)).toEqual(['a.js', 'gone.js']);
    expect(result.current.untracked.map((e) => e.path)).toEqual(['new.txt']);
  });

  test('asks for nothing while the panel shows the file tree', () => {
    const { result } = renderHook(() => useUncommittedChanges({ project: 'kb', enabled: false }));

    expect(gitApi.getStatus).not.toHaveBeenCalled();
    expect(result.current.loading).toBe(false);
    expect(result.current.entries).toEqual([]);
  });

  test('re-asks when the refresh signal says the repository may have changed', async () => {
    gitApi.getStatus.mockResolvedValue([]);

    const { result, rerender } = renderHook((props) => useUncommittedChanges(props), {
      initialProps: { project: 'kb', enabled: true, refreshToken: 0 },
    });
    await waitFor(() => expect(result.current.loading).toBe(false));

    rerender({ project: 'kb', enabled: true, refreshToken: 1 });
    await waitFor(() => expect(gitApi.getStatus).toHaveBeenCalledTimes(2));
  });

  /**
   * Сигнал обновления поднимает каждая правка файла инструментом чата. Если бы
   * список на время перезапроса обнулялся, панель мигала бы на каждую из них —
   * ровно тогда, когда в неё и смотрят.
   */
  test('the previous list stays on screen while the re-ask is in flight', async () => {
    const entry = { path: 'src/App.jsx', status: 'M', additions: 1, deletions: 0 };
    gitApi.getStatus.mockResolvedValue([entry]);

    const { result, rerender } = renderHook((props) => useUncommittedChanges(props), {
      initialProps: { project: 'kb', enabled: true, refreshToken: 0 },
    });
    await waitFor(() => expect(result.current.entries).toHaveLength(1));

    let answer;
    gitApi.getStatus.mockReturnValue(new Promise((resolve) => (answer = resolve)));
    rerender({ project: 'kb', enabled: true, refreshToken: 1 });

    expect(result.current.loading).toBe(true);
    expect(result.current.answered).toBe(true);
    expect(result.current.entries).toEqual([entry]);

    await act(async () => answer([]));
    expect(result.current.entries).toEqual([]);
  });

  /** Ответ другого проекта не устаревший, а чужой: показать его — не мигание, а ложь. */
  test('another project starts from unknown rather than from the previous list', async () => {
    gitApi.getStatus.mockResolvedValue([{ path: 'src/App.jsx', status: 'M', additions: 1, deletions: 0 }]);

    const { result, rerender } = renderHook((props) => useUncommittedChanges(props), {
      initialProps: { project: 'kb', enabled: true, refreshToken: 0 },
    });
    await waitFor(() => expect(result.current.entries).toHaveLength(1));

    gitApi.getStatus.mockReturnValue(new Promise(() => {}));
    rerender({ project: 'other', enabled: true, refreshToken: 0 });

    expect(result.current.answered).toBe(false);
    expect(result.current.entries).toEqual([]);
  });

  test('a failed request is reported, not left loading forever', async () => {
    gitApi.getStatus.mockRejectedValue(new Error('boom'));

    const { result } = renderHook(() => useUncommittedChanges({ project: 'kb', enabled: true }));

    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.error).toBeTruthy();
    expect(result.current.entries).toEqual([]);
  });
});
