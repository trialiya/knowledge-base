import { renderHook, waitFor } from '@testing-library/react';
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

  test('a failed request is reported, not left loading forever', async () => {
    gitApi.getStatus.mockRejectedValue(new Error('boom'));

    const { result } = renderHook(() => useUncommittedChanges({ project: 'kb', enabled: true }));

    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.error).toBeTruthy();
    expect(result.current.entries).toEqual([]);
  });
});
